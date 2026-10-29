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
 * SSDV packets found **in a raw bit stream**, whatever framing carries
 * them, as long as their bytes go out unscrambled and whole: the 16 bits
 * « 0x55 0x66 » (or 0x67, no-FEC) at any bit position open a candidate,
 * the 254 bytes that follow complete it, and [Ssdv.lis] (CRC-32,
 * Reed-Solomon) says whether it is one. A wrong start costs nothing but
 * the check, several candidates run at once.
 *
 * Both polarities are read (which FSK tone is 1, USB or LSB), MSB first
 * — SSDV's own order — and, with [lsbAussi], LSB first too.
 *
 * Packets come out to [surPaquet] with the polarity and order that found
 * them; the same packet is passed once (its CRC as key, for a while).
 */
class ChercheurSsdv(
    private val lsbAussi: Boolean = true,
    /** Bits allowed wrong in « 55 66 » (Reed-Solomon repairs the type byte; the sync byte is not covered). */
    private val tolerance: Int = 0,
    private val surPaquet: (Ssdv.Paquet, String) -> Unit,
) {
    private class Candidat(val variante: Int, val octets: ByteArray, var n: Int = 2, var bit: Int = 0, var cour: Int = 0)

    private val registres = IntArray(4)
    private val candidats = ArrayList<Candidat>()
    private val vus = LinkedHashMap<Long, Boolean>()
    var paquets = 0; private set
    var candidatsOuverts = 0L; private set

    /** The variants: 0 MSB first, 1 inverted MSB first, 2 LSB first, 3 inverted LSB first. */
    private val nbVariantes = if (lsbAussi) 4 else 2

    fun bit(b: Int) {
        for (v in 0 until nbVariantes) {
            val x = if (v and 1 == 1) b xor 1 else b
            // MSB first: shifted in at the bottom; LSB first: at the top of the 16 bits.
            val r = if (v < 2) ((registres[v] shl 1) or x) and 0xFFFF else (registres[v] ushr 1) or (x shl 15)
            registres[v] = r
            // The first byte of the two, then the second (LSB first: the first one is the low half).
            val hi = if (v < 2) r ushr 8 else r and 0xFF
            val lo = if (v < 2) r and 0xFF else r ushr 8
            val mot = (hi shl 8) or lo
            val exact = hi == Ssdv.SYNC && (lo == Ssdv.TYPE_FEC || lo == Ssdv.TYPE_SANS_FEC)
            val proche = !exact && tolerance > 0 && Integer.bitCount(mot xor ((Ssdv.SYNC shl 8) or Ssdv.TYPE_FEC)) <= tolerance
            if ((exact || proche) && candidats.size < MAX_CANDIDATS) {
                val o = ByteArray(Ssdv.TAILLE); o[0] = Ssdv.SYNC.toByte(); o[1] = (if (exact) lo else Ssdv.TYPE_FEC).toByte()
                candidats += Candidat(v, o)
                candidatsOuverts++
                // The bit that closed the sync must not count in the next byte.
                candidats.last().bit = -1
            }
        }
        if (candidats.isEmpty()) return
        val it = candidats.iterator()
        while (it.hasNext()) {
            val c = it.next()
            if (c.bit < 0) { c.bit = 0; continue }
            val x = if (c.variante and 1 == 1) b xor 1 else b
            c.cour = if (c.variante < 2) (c.cour shl 1) or x else c.cour or (x shl c.bit)
            if (++c.bit == 8) {
                c.octets[c.n++] = c.cour.toByte(); c.bit = 0; c.cour = 0
                if (c.n == Ssdv.TAILLE) { it.remove(); termine(c) }
            }
        }
    }

    private fun termine(c: Candidat) {
        val p = Ssdv.lis(c.octets) ?: return
        val cle = java.util.zip.CRC32().apply { update(p.brut) }.value
        if (vus.containsKey(cle)) return
        vus[cle] = true
        while (vus.size > 512) vus.remove(vus.keys.first())
        paquets++
        surPaquet(p, NOMS[c.variante])
    }

    fun bits(l: IntArray) { for (b in l) bit(b) }

    companion object {
        const val MAX_CANDIDATS = 24
        val NOMS = listOf("MSB", "MSB inversé", "LSB", "LSB inversé")
    }
}
