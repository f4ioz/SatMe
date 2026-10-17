/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv.s2

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The tests' channel: SatMe's own modulator, then what the air and the SDR
 * do — the sample clock off ([ppm], a fractional [retard]), the carrier off
 * ([decalageHz], drifting [deriveHzS] per second), phase noise (a random
 * walk of [bruitPhaseRad] per symbol), white noise at [esN0Db] — at the
 * SDR's rate [fs].
 */
class CanalS2(
    val modcod: Modcod,
    val rolloff: Rolloff = Rolloff.A25,
    val rs: Double = 1e6,
    val fs: Double = 2.5e6,
    val esN0Db: Double? = null,
    val decalageHz: Double = 0.0,
    val deriveHzS: Double = 0.0,
    val ppm: Double = 0.0,
    val retard: Double = 0.0,
    val bruitPhaseRad: Double = 0.0,
    val graine: Long = 1,
    /** Packets per frame time ready (1.0: always full frames; less: dummy frames between). */
    val remplissage: Double = 1.0,
) {
    /** The packets sent, numbered (PID 0x100, the number in the payload). */
    val envoyes = ArrayList<ByteArray>()

    private inner class Source : SourceTs {
        var credit = 0.0
        var appels = 0
        override fun disponibles(): Int {
            if (remplissage >= 1.0) return 1_000_000
            // Called once per frame: the credit grows by a fraction of what a frame holds.
            appels++
            credit += remplissage * modcod.dfl / 1504.0
            return credit.toInt()
        }
        override fun lis(dst: ByteArray) {
            val k = envoyes.size
            dst[0] = 0x47; dst[1] = 0x01; dst[2] = 0x00; dst[3] = (0x10 or (k and 15)).toByte()
            dst[4] = (k shr 24).toByte(); dst[5] = (k shr 16).toByte(); dst[6] = (k shr 8).toByte(); dst[7] = k.toByte()
            val r = java.util.Random(k.toLong())
            for (j in 8 until 188) dst[j] = r.nextInt().toByte()
            envoyes += dst.copyOf()
            if (remplissage < 1.0) credit -= 1.0
        }
    }

    /** [secondes] of signal at [fs], complex interleaved. */
    fun signal(secondes: Double): FloatArray {
        val sps = 4
        val src = Source()
        val mod = ModulateurDvbs2(modcod, rolloff, sps, src)
        val fin = rs * sps
        val nOut = (secondes * fs).toInt()
        val ratio = fin / fs * (1 + ppm * 1e-6)
        val nIn = (nOut * ratio + 64).toInt() + 1
        val iq = ShortArray(2 * nIn)
        mod.remplis(iq, nIn)
        val x = FloatArray(2 * nIn) { iq[it] / 32768f }
        // Windowed sinc, cut at the slower rate's Nyquist.
        val demi = 16
        val coupure = minOf(1.0, 1.0 / ratio) * 0.95
        val out = FloatArray(2 * nOut)
        val g = java.util.Random(graine)
        var phase = 0.0
        var p = 0.0
        for (n in 0 until nOut) {
            val tau = n * ratio + retard + demi
            val i0 = floor(tau).toInt()
            var a = 0.0; var b = 0.0
            for (k in i0 - demi + 1..i0 + demi) {
                if (k < 0 || k >= nIn) continue
                val d = tau - k
                val w = 0.42 + 0.5 * cos(PI * d / demi) + 0.08 * cos(2 * PI * d / demi)
                val sinc = if (kotlin.math.abs(d) < 1e-9) coupure else sin(PI * coupure * d) / (PI * d)
                val h = sinc * w
                a += h * x[2 * k]; b += h * x[2 * k + 1]
            }
            val t = n / fs
            val f = decalageHz + deriveHzS * t
            val ph = phase + p
            val c = cos(ph); val s = sin(ph)
            out[2 * n] = (a * c - b * s).toFloat(); out[2 * n + 1] = (a * s + b * c).toFloat()
            phase += 2 * PI * f / fs
            if (phase > PI) phase -= 2 * PI else if (phase < -PI) phase += 2 * PI
            if (bruitPhaseRad > 0) p += g.nextGaussian() * bruitPhaseRad * sqrt(rs / fs)
        }
        if (esN0Db != null) {
            var pw = 0.0
            for (v in out) pw += v * v
            pw /= nOut
            val sigma2 = pw * fs / (rs * Math.pow(10.0, esN0Db / 10))
            val sd = sqrt(sigma2 / 2)
            for (k in out.indices) out[k] += (g.nextGaussian() * sd).toFloat()
        }
        return out
    }

    /** Runs [x] through a receiver, by blocks as an SDR gives them, and waits for its decoders. */
    fun recois(x: FloatArray, rx: RecepteurS2, bloc: Int = 16_384) {
        var k = 0
        val b = FloatArray(2 * bloc)
        while (k < x.size) {
            val n = minOf(2 * bloc, x.size - k)
            System.arraycopy(x, k, b, 0, n)
            rx.echantillons(b, n)
            k += n
        }
        rx.vide()
    }

    companion object {
        /** A packet's number (from [CanalS2]'s payload), or −1 when it is not one of them. */
        fun numero(p: ByteArray, o: Int = 0): Int {
            if (p[o].toInt() != 0x47 || p[o + 1].toInt() and 0x1F != 0x01 || p[o + 2].toInt() != 0) return -1
            return ((p[o + 4].toInt() and 0xFF) shl 24) or ((p[o + 5].toInt() and 0xFF) shl 16) or ((p[o + 6].toInt() and 0xFF) shl 8) or (p[o + 7].toInt() and 0xFF)
        }
    }
}

/** What a receiver gave out, packet by packet, checked against what was sent. */
class Recu(private val canal: CanalS2) {
    val paquets = java.util.Collections.synchronizedList(ArrayList<ByteArray>())
    val sortie: (ByteArray, Int) -> Unit = { b, n -> var o = 0; while (o + 188 <= n) { paquets += b.copyOfRange(o, o + 188); o += 188 } }

    /** Packets intact (byte for byte as sent), wrong, and the sent span they cover. */
    fun bilan(): Triple<Int, Int, Int> {
        var bons = 0; var faux = 0
        var min = Int.MAX_VALUE; var max = -1
        for (p in paquets.toList()) {
            val k = CanalS2.numero(p)
            if (k < 0 || k >= canal.envoyes.size) { faux++; continue }
            if (p.contentEquals(canal.envoyes[k])) { bons++; min = minOf(min, k); max = maxOf(max, k) } else faux++
        }
        return Triple(bons, faux, if (max >= min) max - min + 1 else 0)
    }
}
