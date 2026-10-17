/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A receiver for the automatic tuning's tests: a sound shifted in frequency
 * (analytic signal, no resampling) by a mistuning that may drift, less what
 * the tuning has moved, decoded as SatMe does.
 */
object AfcBanc {

    /** Shifts a real sound in frequency: Hilbert FIR for the analytic signal, then a complex mixer. */
    class Decaleur(private val rate: Int, taps: Int = 127) {
        private val m = taps / 2
        private val h = DoubleArray(taps) { i ->
            val k = i - m
            if (k % 2 == 0) 0.0 else {
                // Blackman window.
                val w = 0.42 - 0.5 * cos(2 * PI * i / (taps - 1)) + 0.08 * cos(4 * PI * i / (taps - 1))
                2.0 / (PI * k) * w
            }
        }
        private val ligne = DoubleArray(taps)
        private var pos = 0
        private var phase = 0.0

        fun traite(x: ShortArray, n: Int, out: ShortArray, hz: Double) {
            val dp = 2 * PI * hz / rate
            for (i in 0 until n) {
                ligne[pos] = x[i].toDouble()
                var q = 0.0
                var j = pos
                for (k in h.indices) { q += h[k] * ligne[j]; j--; if (j < 0) j = ligne.size - 1 }
                var d = pos - m; if (d < 0) d += ligne.size
                val re = ligne[d]
                pos = (pos + 1) % ligne.size
                val y = re * cos(phase) - q * sin(phase)
                phase += dp
                if (phase > PI) phase -= 2 * PI else if (phase < -PI) phase += 2 * PI
                out[i] = y.roundToInt().coerceIn(-32767, 32767).toShort()
            }
        }
    }

    /** A receiver's SSB filter: [bas] to [haut] Hz, windowed-sinc FIR. */
    class Filtre(rate: Int, bas: Double = 300.0, haut: Double = 2700.0, taps: Int = 101) {
        private val m = taps / 2
        private val h = DoubleArray(taps) { i ->
            val k = i - m
            val w = 0.42 - 0.5 * cos(2 * PI * i / (taps - 1)) + 0.08 * cos(4 * PI * i / (taps - 1))
            fun lp(fc: Double) = if (k == 0) 2 * fc / rate else sin(2 * PI * fc / rate * k) / (PI * k)
            (lp(haut) - lp(bas)) * w
        }
        private val ligne = DoubleArray(taps)
        private var pos = 0
        fun traite(x: ShortArray, n: Int) {
            for (i in 0 until n) {
                ligne[pos] = x[i].toDouble()
                var s = 0.0; var j = pos
                for (k in h.indices) { s += h[k] * ligne[j]; j--; if (j < 0) j = ligne.size - 1 }
                pos = (pos + 1) % ligne.size
                x[i] = s.roundToInt().coerceIn(-32767, 32767).toShort()
            }
        }
    }

    class Image(val mode: SstvMode, val pixels: IntArray, val lignes: Int)

    class Sortie(val images: List<Image>, val totalHz: Int, val pas: List<Int>, val resteFinHz: Double, val corrections: Int,
                 val journal: List<String> = emptyList())

    /**
     * Decodes [pcm] mistuned by [decalage] (Hz, at time in seconds), white
     * noise of [bruit] rms added first. With [afc], the tuning moves the
     * receiver: at once ([couplee]) or [latenceMs] later. [ssb]: through a
     * 300–2700 Hz filter, as a receiver's.
     */
    fun decode(pcm: ShortArray, rate: Int, decalage: (Double) -> Double, afc: Boolean, couplee: Boolean = true,
               latenceMs: Int = 0, bruit: Double = 0.0, sens: Int = 1, graine: Long = 1L, bloc: Int = 1024,
               ssb: Boolean = false, pendantImage: Boolean = true, sonde: ((SstvAfc, SstvDecoder, Double) -> Unit)? = null): Sortie {
        val images = ArrayList<Image>()
        val dec = SstvDecoder(rate, object : SstvDecoder.Listener {
            override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) {
                if (linesDone >= mode.height / 8) images += Image(mode, pixels.copyOf(), linesDone)
            }
        }).also { it.continuous = true }
        val a = if (afc) SstvAfc(rate, pendantImage) else null
        val pas = ArrayList<Int>()
        val journal = ArrayList<String>()
        // The receiver: where it was asked to be, and where it is (the sound comes back later when not coupled).
        var demande = 0L
        var reel = 0L
        val enVol = ArrayDeque<Pair<Long, Long>>()
        val cible = object : CibleAfc {
            override fun sens() = sens
            override val couplee = couplee
            override fun position() = demande
            override fun deplace(hz: Int) { pas += hz; demande += hz }
        }
        val rnd = Random(graine)
        val d = Decaleur(rate)
        val filtre = if (ssb) Filtre(rate) else null
        val x = ShortArray(bloc); val y = ShortArray(bloc)
        var i = 0
        while (i < pcm.size) {
            val n = minOf(bloc, pcm.size - i)
            for (k in 0 until n) x[k] = pcm[i + k]
            if (couplee || latenceMs == 0) reel = demande
            else while (enVol.isNotEmpty() && enVol.first().first <= i) reel = enVol.removeFirst().second
            // The receiver moved up by `reel` (receiver Hz): the tones go down by sens * reel.
            d.traite(x, n, y, decalage(i.toDouble() / rate) - sens * reel)
            filtre?.traite(y, n)
            // The receiver's noise, after the tuning: it does not move with it.
            if (bruit > 0) for (k in 0 until n) y[k] = (y[k] + rnd.nextGaussian() * bruit).roundToInt().coerceIn(-32767, 32767).toShort()
            if (a == null) dec.feed(y, n) else {
                val avant = demande
                a.nourrit(dec, y, n, cible)
                sonde?.invoke(a, dec, (i + n).toDouble() / rate)
                if (!couplee && latenceMs > 0 && demande != avant) enVol.addLast((i + n + latenceMs.toLong() * rate / 1000) to demande)
                if (demande != avant) journal += "%.1f s %+d (en-tête %s, mesure %s, image %b)".format((i + n).toDouble() / rate, demande - avant, a.etat.visHz, a.etat.mesureHz, dec.decoding)
            }
            i += n
        }
        dec.finish()
        val fin = decalage(pcm.size.toDouble() / rate) - sens * demande
        return Sortie(images, a?.totalHz ?: 0, pas, fin, a?.etat?.corrections ?: 0, journal)
    }

    /** Mean absolute difference per colour channel (0..255) over the lines both have. */
    fun ecart(a: Image, b: Image): Double {
        if (a.mode != b.mode) return 255.0
        val w = a.mode.width
        val h = minOf(a.lignes, b.lignes)
        if (h <= 0) return 255.0
        var s = 0L
        for (p in 0 until w * h) {
            val u = a.pixels[p]; val v = b.pixels[p]
            s += abs((u shr 16 and 255) - (v shr 16 and 255)) + abs((u shr 8 and 255) - (v shr 8 and 255)) + abs((u and 255) - (v and 255))
        }
        return s / (3.0 * w * h)
    }

    /** A mono 16-bit WAV, whatever chunks precede its data. */
    fun litWav(f: File): Pair<ShortArray, Int>? = runCatching {
        RandomAccessFile(f, "r").use { r ->
            val t = ByteArray(12); r.readFully(t)
            if (String(t, 0, 4) != "RIFF" || String(t, 8, 4) != "WAVE") return null
            var rate = 0; var canaux = 1
            while (r.filePointer + 8 <= r.length()) {
                val c = ByteArray(8); r.readFully(c)
                val id = String(c, 0, 4)
                val taille = ByteBuffer.wrap(c, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                if (id == "fmt ") {
                    val b = ByteArray(taille.toInt()); r.readFully(b)
                    val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                    canaux = bb.getShort(2).toInt(); rate = bb.getInt(4)
                    if (bb.getShort(14).toInt() != 16) return null
                } else if (id == "data") {
                    val n = (minOf(taille, r.length() - r.filePointer) / 2 / canaux).toInt()
                    val o = ByteArray(n * 2 * canaux); r.readFully(o)
                    val bb = ByteBuffer.wrap(o).order(ByteOrder.LITTLE_ENDIAN)
                    return ShortArray(n) { val v = bb.getShort(); repeat(canaux - 1) { bb.getShort() }; v } to rate
                } else r.seek(r.filePointer + taille + (taille and 1))
            }
            null
        }
    }.getOrNull()
}
