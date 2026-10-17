/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv.dvbs

/**
 * A DVB-S receiver for the tests, at perfect timing and carrier, written from
 * the standard's receive side and sharing nothing with the modulator but the
 * code rate's name: depuncturing (erasures), soft Viterbi, convolutional
 * de-interleaving, sync search, RS(204,188) errors-only decoding
 * (Berlekamp-Massey, Chien, Forney), energy dispersal removed.
 */
class RecepteurDvbsEssai(val taux: TauxDvbs) {

    class Bilan {
        val paquets = ArrayList<ByteArray>()
        var octetsCorriges = 0
        var paquetsFaux = 0
        var bitsViterbi = 0
    }

    // ------------------------------------------------------------ GF(256), p(x) = x⁸+x⁴+x³+x²+1

    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var v = 1
        for (i in 0 until 255) { exp[i] = v; log[v] = i; v = (v shl 1).let { if (it >= 256) it xor 0x11D else it } }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }

    private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
    private fun div(a: Int, b: Int) = if (a == 0) 0 else exp[(log[a] - log[b] + 255) % 255]
    private fun pow(i: Int) = exp[((i % 255) + 255) % 255]

    // ------------------------------------------------------------ Viterbi

    /**
     * Soft QPSK symbols (I, Q interleaved; > 0: bit 0), the first one the
     * first sent, to the decoded bits (one per byte).
     */
    fun viterbi(s: FloatArray): ByteArray {
        val x = taux.x.map { it == '1' }
        val y = taux.y.map { it == '1' }
        val p = taux.periode
        // Serial soft bits → per input bit (sx, sy), 0 where punctured.
        val nSerie = s.size
        val parPeriode = x.count { it } + y.count { it }
        val nBits = nSerie / parPeriode * p
        val sx = FloatArray(nBits); val sy = FloatArray(nBits)
        var k = 0
        for (b in 0 until nBits) {
            val ph = b % p
            if (x[ph]) sx[b] = s[k++]
            if (y[ph]) sy[b] = s[k++]
        }
        // Outputs of each (state, input): state = 6 last inputs, most recent in bit 5.
        val ox = IntArray(128); val oy = IntArray(128)
        for (r in 0 until 128) { ox[r] = Integer.bitCount(r and 0b1111001) and 1; oy[r] = Integer.bitCount(r and 0b1011011) and 1 }
        var m = DoubleArray(64) { if (it == 0) 0.0 else -1e30 }
        var n = DoubleArray(64)
        val choix = LongArray(nBits)
        for (b in 0 until nBits) {
            var c = 0L
            for (ns in 0 until 64) {
                val u = ns shr 5
                var best = -1e300; var bb = 0
                for (lsb in 0..1) {
                    val ps = ((ns and 0x1F) shl 1) or lsb
                    val r = (u shl 6) or ps
                    val v = m[ps] + (if (ox[r] == 0) sx[b] else -sx[b]) + (if (oy[r] == 0) sy[b] else -sy[b])
                    if (v > best) { best = v; bb = lsb }
                }
                n[ns] = best
                if (bb == 1) c = c or (1L shl ns)
            }
            choix[b] = c
            val t = m; m = n; n = t
        }
        var st = (0 until 64).maxByOrNull { m[it] }!!
        val bits = ByteArray(nBits)
        for (b in nBits - 1 downTo 0) {
            bits[b] = (st shr 5).toByte()
            val lsb = ((choix[b] shr st) and 1L).toInt()
            st = ((st and 0x1F) shl 1) or lsb
        }
        return bits
    }

    // ------------------------------------------------------------ de-interleaving

    /** Bytes (MSB first) through the de-interleaver: branch j delayed (11 − j) × 17 bytes. */
    fun desentrelace(bits: ByteArray): ByteArray {
        val n = bits.size / 8
        val fifos = Array(12) { j -> ArrayDeque<Byte>().apply { repeat((11 - j) * 17) { addLast(0) } } }
        return ByteArray(n) { i ->
            var v = 0
            for (j in 0 until 8) v = (v shl 1) or bits[8 * i + j].toInt()
            val f = fifos[i % 12]
            f.addLast(v.toByte())
            f.removeFirst()
        }
    }

    // ------------------------------------------------------------ RS(204,188)

    /** Corrects [c] (204 bytes) in place: the bytes corrected, or −1 when it cannot. */
    fun rs(c: ByteArray): Int {
        val n = 204
        val r = IntArray(n) { c[it].toInt() and 0xFF }
        val syn = IntArray(16) { i -> var v = 0; for (k in 0 until n) v = mul(v, pow(i)) xor r[k]; v }
        if (syn.all { it == 0 }) return 0
        // Berlekamp-Massey.
        var lam = IntArray(17).also { it[0] = 1 }
        var prec = IntArray(17).also { it[0] = 1 }
        var l = 0; var mm = 1; var bq = 1
        for (i in 0 until 16) {
            var d = syn[i]
            for (j in 1..l) d = d xor mul(lam[j], syn[i - j])
            if (d == 0) { mm++; continue }
            val t = lam.copyOf()
            val coef = div(d, bq)
            for (j in 0 until 17 - mm) lam[j + mm] = lam[j + mm] xor mul(coef, prec[j])
            if (2 * l <= i) { l = i + 1 - l; prec = t; bq = d; mm = 1 } else mm++
        }
        if (l > 8) return -1
        // Ω = S·Λ mod x¹⁶.
        val om = IntArray(16)
        for (i in 0 until 16) { var v = 0; for (j in 0..minOf(i, 16)) v = v xor mul(lam[j], syn[i - j]); om[i] = v }
        var trouves = 0
        for (k in 0 until n) {
            // Position k: x^(203 − k); X⁻¹ = α^−(203 − k).
            val xi = pow(-(203 - k))
            var v = 0; var pw = 1
            for (j in 0..l) { v = v xor mul(lam[j], pw); pw = mul(pw, xi) }
            if (v != 0) continue
            var o = 0; pw = 1
            for (j in 0 until 16) { o = o xor mul(om[j], pw); pw = mul(pw, xi) }
            var dl = 0; pw = 1
            // Λ'(x) = Σ odd j Λⱼ x^(j−1).
            for (j in 1..l) { if (j % 2 == 1) dl = dl xor mul(lam[j], pw); pw = mul(pw, xi) }
            if (dl == 0) return -1
            val e = mul(pow(203 - k), div(o, dl))
            r[k] = r[k] xor e
            trouves++
        }
        if (trouves != l) return -1
        for (k in 0 until n) c[k] = r[k].toByte()
        return trouves
    }

    // ------------------------------------------------------------ energy dispersal

    private val prbs: ByteArray = run {
        // 1 + X¹⁴ + X¹⁵, cells 1…15 loaded with 1 0 0 1 0 1 0 1 0 0 0 0 0 0 0.
        val cell = intArrayOf(1, 0, 0, 1, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0)
        ByteArray(1503) {
            var v = 0
            repeat(8) {
                val o = cell[13] xor cell[14]
                for (j in 14 downTo 1) cell[j] = cell[j - 1]
                cell[0] = o
                v = (v shl 1) or o
            }
            v.toByte()
        }
    }

    // ------------------------------------------------------------ the whole chain

    fun decode(s: FloatArray): Bilan {
        val b = Bilan()
        val bits = viterbi(s)
        b.bitsViterbi = bits.size
        val o = desentrelace(bits)
        // Sync: a 0xB8 then 7 × 0x47 every 204 bytes (6 of 8 at least).
        fun sync(p: Int) = o[p].toInt() and 0xFF
        var debut = -1
        for (p in 0 until o.size - 8 * 204) {
            if (sync(p) != 0xB8) continue
            if ((1 until 8).count { sync(p + 204 * it) == 0x47 } >= 6) { debut = p; break }
        }
        check(debut >= 0) { "pas de synchronisation" }
        // Back to the first whole packet, its place in the group of 8 counted back from the 0xB8.
        var p = debut % 204
        var g = (8 - ((debut - p) / 204) % 8) % 8
        val mot = ByteArray(204)
        // Words before the first good one: the de-interleaver's start, not counted.
        var accroche = false
        while (p + 204 <= o.size) {
            System.arraycopy(o, p, mot, 0, 204)
            val c = rs(mot)
            if (c < 0) {
                if (accroche) { b.paquetsFaux++; b.paquets += ByteArray(0) }
                p += 204; g = (g + 1) and 7; continue
            }
            accroche = true
            b.octetsCorriges += c
            if ((mot[0].toInt() and 0xFF) == 0xB8) g = 0
            val pq = ByteArray(188)
            pq[0] = 0x47
            for (k in 1 until 188) pq[k] = (mot[k].toInt() xor prbs[g * 188 + k - 1].toInt()).toByte()
            b.paquets += pq
            p += 204
            g = (g + 1) and 7
        }
        return b
    }
}
