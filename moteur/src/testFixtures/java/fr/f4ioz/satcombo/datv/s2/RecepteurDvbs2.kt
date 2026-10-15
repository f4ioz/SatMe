/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv.s2

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A DVB-S2 receiver for the tests, at perfect timing and carrier: written
 * from the standard's receive side, sharing only the tables with the
 * modulator. Matched filter, PLHEADER decoded by correlation over all 128
 * PLS codewords, PL descrambling, pilots removed, soft demapping, LDPC
 * syndrome check then min-sum decoding when needed, BCH divisibility check,
 * BB descrambling, BBHEADER CRC-8, packets cut with SYNCD and their CRC-8
 * checked against the next packet's first byte.
 */
class RecepteurDvbs2(val rolloff: Rolloff, val sps: Int, val portee: Int = 16) {

    class Bilan {
        val paquets = ArrayList<ByteArray>()
        var tramesVides = 0
        var tramesDonnees = 0
        var bitsCorriges = 0
        var ldpcEchecs = 0
        var crcPaquetsFaux = 0
        val modcods = HashSet<Int>()
    }

    /** The matched filter's output at the symbol instants, gain normalised (|s| ≈ 1). */
    fun symboles(iq: ShortArray): FloatArray = symboles(FloatArray(iq.size) { iq[it].toFloat() })

    fun symboles(x: FloatArray): FloatArray {
        val h = FiltreRrc(rolloff.alpha, sps, portee).coefs
        val d = portee * sps
        val n = x.size / 2
        val nSym = (n - 2 * d - 1) / sps
        val s = FloatArray(2 * nSym)
        for (k in 0 until nSym) {
            val c = k * sps + 2 * d
            var a = 0.0; var b = 0.0
            for (j in h.indices) {
                val idx = c - j
                if (idx < 0) break
                a += h[j] * x[2 * idx]; b += h[j] * x[2 * idx + 1]
            }
            s[2 * k] = a.toFloat(); s[2 * k + 1] = b.toFloat()
        }
        var p = 0.0
        for (v in s) p += v * v
        val g = sqrt(p / nSym).toFloat()
        for (k in s.indices) s[k] /= g
        return s
    }

    private val codes = Array(128) { c -> CouchePhysique.pls(c shr 2, c and 3) }

    /** Decodes the symbols from a PLFRAME start to the last whole frame. */
    fun decode(s: FloatArray): Bilan {
        val b = Bilan()
        val flux = java.io.ByteArrayOutputStream()
        var premierSyncd = -1
        var pos = 0
        val nSym = s.size / 2
        while (pos + 90 <= nSym) {
            val y = FloatArray(90) { k -> val i = s[2 * (pos + k)]; val q = s[2 * (pos + k) + 1]; if (k % 2 == 0) i + q else q - i }
            // SOF: (nearly) all 26 bits agree at perfect sync.
            val fautes = (0 until 26).count { k -> (y[k] < 0) != (((CouchePhysique.SOF shr (25 - k)) and 1) == 1) }
            check(fautes <= 4) { "SOF faux au symbole $pos ($fautes bits)" }
            var meilleur = -1; var max = Float.NEGATIVE_INFINITY
            for (c in 0 until 128) {
                var v = 0f
                for (k in 0 until 64) {
                    val bit = codes[c][k] xor (CouchePhysique.BROUILLAGE_PLS[k] - '0')
                    v += if (bit == 0) y[26 + k] else -y[26 + k]
                }
                if (v > max) { max = v; meilleur = c }
            }
            val code = meilleur shr 2; val type = meilleur and 3
            if (code == 0) {
                if (pos + CouchePhysique.SYMBOLES_VIDE > nSym) break
                b.tramesVides++; pos += CouchePhysique.SYMBOLES_VIDE; continue
            }
            val m = Modcod.tous((type and 2) != 0, (type and 1) != 0).first { it.code == code }
            if (pos + m.symboles > nSym) break
            b.modcods += code
            val charge = desembrouille(s, pos, m)
            val llr = demappe(m, charge)
            val bits = decodeLdpc(m, llr, b)
            if (bits == null) { pos += m.symboles; continue }
            check(bchDivisible(m, bits)) { "mot BCH faux" }
            BrouilleurBb.applique(bits, m.kbch)
            val o = ByteArray(m.kbch / 8) { k -> var v = 0; for (j in 0 until 8) v = (v shl 1) or bits[8 * k + j].toInt(); v.toByte() }
            check(Crc8.octets(o, 0, 9) == (o[9].toInt() and 0xFF)) { "CRC de l'en-tête BB faux" }
            check((o[0].toInt() and 0xFC) == 0xF0 && (o[0].toInt() and 3) == rolloff.ro) { "MATYPE inattendu" }
            val upl = ((o[2].toInt() and 0xFF) shl 8) or (o[3].toInt() and 0xFF)
            val dfl = ((o[4].toInt() and 0xFF) shl 8) or (o[5].toInt() and 0xFF)
            val syncd = ((o[7].toInt() and 0xFF) shl 8) or (o[8].toInt() and 0xFF)
            check(upl == 1504 && dfl == m.dfl && o[6].toInt() == 0x47)
            // SYNCD agrees with where the stream's packets fall.
            if (premierSyncd < 0) premierSyncd = syncd / 8 + flux.size()
            else check(((flux.size() - premierSyncd) % 188 + 188) % 188 == (188 - syncd / 8) % 188) { "SYNCD incohérent" }
            flux.write(o, 10, dfl / 8)
            b.tramesDonnees++
            pos += m.symboles
        }
        // The packets: each one's first byte is the CRC-8 of the one before.
        val f = flux.toByteArray()
        var k = maxOf(premierSyncd, 0)
        var precedent: ByteArray? = null
        while (k + 188 <= f.size) {
            val p = f.copyOfRange(k, k + 188)
            val crc = p[0].toInt() and 0xFF
            if (precedent != null && Crc8.octets(precedent, 1, 187) != crc) b.crcPaquetsFaux++
            p[0] = 0x47
            if (precedent != null) b.paquets += precedent
            precedent = p
            k += 188
        }
        // The last one's CRC comes with the next packet: kept unchecked.
        precedent?.let { b.paquets += it }
        return b
    }

    /** The frame's data symbols (header off, PL descrambled, pilots out). */
    private fun desembrouille(s: FloatArray, pos: Int, m: Modcod): FloatArray {
        val r = CouchePhysique.SEQUENCE_PL
        val sortie = FloatArray(2 * m.nldpc / m.constellation.bits)
        var o = 0
        var k = 0
        val n = m.symboles - 90
        var dansSlot = 0; var slots = 0
        while (k < n) {
            val i = s[2 * (pos + 90 + k)]; val q = s[2 * (pos + 90 + k) + 1]
            // Multiply by exp(−jRπ/2).
            val (a, c) = when (r[k].toInt()) { 0 -> i to q; 1 -> q to -i; 2 -> -i to -q; else -> -q to i }
            k++
            sortie[o++] = a; sortie[o++] = c
            if (++dansSlot == 90) {
                dansSlot = 0; slots++
                if (m.pilotes && slots % 16 == 0 && slots < m.slots) k += 36
            }
        }
        return sortie
    }

    /** Soft values (positive: bit 0), in the FECFRAME's order. */
    private fun demappe(m: Modcod, x: FloatArray): FloatArray {
        val n = m.nldpc
        val l = FloatArray(n)
        when (m.constellation) {
            Constellation.QPSK -> for (k in 0 until n) l[k] = 4f * x[k] * SQ2
            Constellation.PSK8 -> {
                val lu = FloatArray(n)
                for (sy in 0 until n / 3) {
                    val i = x[2 * sy]; val q = x[2 * sy + 1]
                    val d = FloatArray(8) { w -> val a = i - CouchePhysique.PSK8_I[w]; val c = q - CouchePhysique.PSK8_Q[w]; -(a * a + c * c) * 4f }
                    for (bit in 0 until 3) {
                        var m0 = Float.NEGATIVE_INFINITY; var m1 = Float.NEGATIVE_INFINITY
                        for (w in 0 until 8) if ((w shr (2 - bit)) and 1 == 0) m0 = maxOf(m0, d[w]) else m1 = maxOf(m1, d[w])
                        lu[3 * sy + bit] = m0 - m1
                    }
                }
                // Undo the interleaver: symbol r's bits came from rows r of the columns.
                val lignes = n / 3
                val ordre = if (m.taux == Taux.R3_5) intArrayOf(2, 1, 0) else intArrayOf(0, 1, 2)
                for (r in 0 until lignes) for (j in 0 until 3) l[ordre[j] * lignes + r] = lu[3 * r + j]
            }
        }
        return l
    }

    /** The parity-check matrix's rows, built from the annex's table (check j: info bits + p_j + p_{j−1}). */
    private class Graphe(m: Modcod) {
        val n = m.nldpc
        val k = m.kldpc
        val r = n - k
        val lignes: Array<IntArray>

        init {
            val t = TablesLdpc.lignes(m.taux, m.courte)!!
            val q = r / 360
            val listes = Array(r) { ArrayList<Int>(12) }
            for (g in t.indices) for (j in 0 until 360) for (x in t[g]) listes[(x + j * q) % r] += g * 360 + j
            for (c in 0 until r) { listes[c] += k + c; if (c > 0) listes[c] += k + c - 1 }
            lignes = Array(r) { listes[it].toIntArray() }
        }

        fun syndromeNul(bits: ByteArray): Boolean = lignes.all { l -> l.fold(0) { a, v -> a xor bits[v].toInt() } == 0 }
    }

    private val graphes = HashMap<Int, Graphe>()

    private fun decodeLdpc(m: Modcod, llr: FloatArray, b: Bilan): ByteArray? {
        val g = graphes.getOrPut(m.code * 4 + m.type) { Graphe(m) }
        val dur = ByteArray(g.n) { if (llr[it] < 0) 1 else 0 }
        if (g.syndromeNul(dur)) return dur
        // Offset min-sum, flooding schedule.
        val msg = Array(g.r) { FloatArray(g.lignes[it].size) }
        val total = FloatArray(g.n)
        val bits = ByteArray(g.n)
        repeat(60) {
            for (v in 0 until g.n) total[v] = llr[v]
            for (c in 0 until g.r) { val l = g.lignes[c]; for (e in l.indices) total[l[e]] += msg[c][e] }
            for (c in 0 until g.r) {
                val l = g.lignes[c]; val mc = msg[c]
                var signe = 1f; var m1 = Float.MAX_VALUE; var m2 = Float.MAX_VALUE; var im = -1
                val entree = FloatArray(l.size)
                for (e in l.indices) {
                    val v = total[l[e]] - mc[e]
                    entree[e] = v
                    if (v < 0) signe = -signe
                    val a = abs(v)
                    if (a < m1) { m2 = m1; m1 = a; im = e } else if (a < m2) m2 = a
                }
                for (e in l.indices) {
                    val mag = 0.8f * (if (e == im) m2 else m1)
                    val s = if (entree[e] < 0) -signe else signe
                    mc[e] = s * mag
                }
            }
            for (v in 0 until g.n) total[v] = llr[v]
            for (c in 0 until g.r) { val l = g.lignes[c]; for (e in l.indices) total[l[e]] += msg[c][e] }
            for (v in 0 until g.n) bits[v] = if (total[v] < 0) 1 else 0
            if (g.syndromeNul(bits)) {
                b.bitsCorriges += (0 until g.n).count { bits[it] != dur[it] }
                return bits
            }
        }
        b.ldpcEchecs++
        return null
    }

    /** The BCH codeword (first kldpc bits) divisible by each of its g_i(x). */
    private fun bchDivisible(m: Modcod, bits: ByteArray): Boolean {
        val polys = if (m.courte) Bch.POLYS_COURTES else Bch.POLYS_NORMALES
        for (k in 0 until m.t) if (!BchVerif.divisible(bits, m.kldpc, polys[k])) return false
        return true
    }

    companion object {
        private val SQ2 = sqrt(2.0).toFloat()
    }
}

/** Polynomial remainder by plain long division, independent of the encoder's register. */
object BchVerif {
    /** c(x) = bits[0]·x^(n−1) + … + bits[n−1] divisible by g (exponents)? */
    fun divisible(bits: ByteArray, n: Int, g: IntArray): Boolean {
        val deg = g.max()
        val reste = IntArray(deg)
        val gm = IntArray(deg).also { for (e in g) if (e < deg) it[e] = 1 }
        // Horner: r = r·x + bit, reduced by g at each step.
        for (k in 0 until n) {
            val haut = reste[deg - 1]
            for (j in deg - 1 downTo 1) reste[j] = reste[j - 1]
            reste[0] = bits[k].toInt()
            if (haut == 1) for (j in 0 until deg) reste[j] = reste[j] xor gm[j]
        }
        return reste.all { it == 0 }
    }
}
