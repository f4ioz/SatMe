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
 * GF(2^8) built on x^8 + x^4 + x^3 + x^2 + 1 (0x11D), primitive element
 * α = x (2): the field both FX.25 and IL2P use.
 */
object Gf256 {
    val exp = IntArray(512)
    val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x; log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }

    fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
    fun div(a: Int, b: Int): Int = if (a == 0) 0 else exp[(log[a] - log[b] + 255) % 255]
    /** α^i, any i. */
    fun alpha(i: Int): Int = exp[((i % 255) + 255) % 255]
}

/**
 * A Reed-Solomon code over [Gf256] with [nRacines] check bytes: generator
 * g(x) = Π (x − α^(premiereRacine + i)), i = 0 … nRacines − 1. Systematic:
 * the data bytes, then the check bytes. Shortened codes (fewer than 255
 * bytes in all) are handled as the full code with leading zeros.
 *
 * FX.25 uses first root 1, IL2P first root 0 (IL2P specification draft
 * v0.6, "The RS encoder uses zero as its first root").
 *
 * The decoder: syndromes, Berlekamp-Massey, Chien search, Forney — written
 * here from the textbook algorithms.
 */
class ReedSolomon(val nRacines: Int, val premiereRacine: Int) {
    init { require(nRacines in 2..254 && nRacines % 2 == 0) }

    /** Generator coefficients, highest degree first (g[0] = 1). */
    private val g: IntArray = run {
        var p = intArrayOf(1)
        for (i in 0 until nRacines) {
            val r = Gf256.alpha(premiereRacine + i)
            val q = IntArray(p.size + 1)
            for (j in p.indices) { q[j] = q[j] xor p[j]; q[j + 1] = q[j + 1] xor Gf256.mul(p[j], r) }
            p = q
        }
        p
    }

    /** The check bytes of [n] data bytes from [debut]. */
    fun parite(donnees: ByteArray, debut: Int = 0, n: Int = donnees.size - debut): ByteArray {
        require(n + nRacines <= 255)
        val reste = IntArray(nRacines)
        for (i in debut until debut + n) {
            val f = (donnees[i].toInt() and 0xFF) xor reste[0]
            for (j in 0 until nRacines - 1) reste[j] = reste[j + 1] xor Gf256.mul(f, g[j + 1])
            reste[nRacines - 1] = Gf256.mul(f, g[nRacines])
        }
        return ByteArray(nRacines) { reste[it].toByte() }
    }

    /**
     * Corrects in place the code block of [n] bytes (data then check bytes)
     * at [debut]. Returns the number of bytes corrected (0: it was right),
     * or −1 when there are more errors than the code can mend — then the
     * block is left as it was.
     */
    fun corrige(bloc: ByteArray, debut: Int = 0, n: Int = bloc.size - debut): Int {
        require(n in nRacines + 1..255)
        val s = syndromes(bloc, debut, n)
        if (s.all { it == 0 }) return 0

        // Berlekamp-Massey: the error locator Λ(x), lowest degree first.
        var lambda = IntArray(nRacines + 1).also { it[0] = 1 }
        var b = IntArray(nRacines + 1).also { it[0] = 1 }
        var l = 0
        var m = 1
        var bb = 1
        for (k in 0 until nRacines) {
            var d = s[k]
            for (i in 1..l) d = d xor Gf256.mul(lambda[i], s[k - i])
            if (d == 0) { m++; continue }
            val coef = Gf256.div(d, bb)
            val t = lambda.copyOf()
            for (i in 0..nRacines - m) lambda[i + m] = lambda[i + m] xor Gf256.mul(coef, b[i])
            if (2 * l <= k) { l = k + 1 - l; b = t; bb = d; m = 1 } else m++
        }
        var deg = nRacines
        while (deg > 0 && lambda[deg] == 0) deg--
        if (deg != l || l > nRacines / 2) return -1

        // Chien search over the block's own positions: index i ↔ X = α^(n−1−i).
        val positions = IntArray(l)
        var trouvees = 0
        for (i in 0 until n) {
            val xInv = Gf256.alpha(-(n - 1 - i))
            var v = 0
            var p = 1
            for (j in 0..l) { v = v xor Gf256.mul(lambda[j], p); p = Gf256.mul(p, xInv) }
            if (v == 0) { if (trouvees == l) return -1; positions[trouvees++] = i }
        }
        if (trouvees != l) return -1

        // Forney: Ω(x) = S(x)·Λ(x) mod x^nRacines; e = X^(1−fcr)·Ω(X⁻¹) / Λ'(X⁻¹).
        val omega = IntArray(nRacines)
        for (i in 0 until nRacines) {
            var v = 0
            for (j in 0..minOf(i, l)) v = v xor Gf256.mul(lambda[j], s[i - j])
            omega[i] = v
        }
        val valeurs = IntArray(l)
        for (k in 0 until l) {
            val e = n - 1 - positions[k]
            val xInv = Gf256.alpha(-e)
            var om = 0; var p = 1
            for (i in 0 until nRacines) { om = om xor Gf256.mul(omega[i], p); p = Gf256.mul(p, xInv) }
            // Λ'(x): the odd terms, one degree down.
            var der = 0; p = 1
            val x2 = Gf256.mul(xInv, xInv)
            var j = 1
            while (j <= l) { der = der xor Gf256.mul(lambda[j], p); p = Gf256.mul(p, x2); j += 2 }
            if (der == 0) return -1
            valeurs[k] = Gf256.mul(Gf256.alpha(e * (1 - premiereRacine)), Gf256.div(om, der))
        }
        val avant = bloc.copyOfRange(debut, debut + n)
        for (k in 0 until l) {
            val i = debut + positions[k]
            bloc[i] = ((bloc[i].toInt() and 0xFF) xor valeurs[k]).toByte()
        }
        // Checked: a word beyond the code's reach can look mendable.
        if (syndromes(bloc, debut, n).any { it != 0 }) { avant.copyInto(bloc, debut); return -1 }
        return l
    }

    private fun syndromes(bloc: ByteArray, debut: Int, n: Int): IntArray = IntArray(nRacines) { j ->
        val r = Gf256.alpha(premiereRacine + j)
        var v = 0
        for (i in debut until debut + n) v = Gf256.mul(v, r) xor (bloc[i].toInt() and 0xFF)
        v
    }
}
