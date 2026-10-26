/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import fr.f4ioz.satcombo.sdr.Fft
import fr.f4ioz.satcombo.sdr.SourceIq
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * QO-100's wide band seen by our own SDR, on a splitter after the same LNB
 * as the MiniTiouner (its IF, 739 to 750 MHz): the 9 MHz of [BandeLarge]
 * swept in steps as wide as the SDR's rate allows (an RTL-SDR's 2.4 MS/s:
 * five steps), each step's spectrum averaged and put in its place, the
 * whole on the ground station's scale (noise at [BandeLarge.BRUIT],
 * [BandeLarge.UNITES_PAR_DB] a dB) — so that its signals are found and
 * named as the BATC's, and a click tunes the MiniTiouner the same way.
 */
object SpectreLocal {
    /** Points across the 9 MHz (10 kHz each). */
    const val POINTS = 900
    /** The FFT's size and how many are averaged a step. */
    const val NFFT = 2048
    const val MOYENNES = 32
    /** The share of each step's band used (its edges roll off in the SDR's filters). */
    const val UTILE = 0.8
    /** Bins around the centre left out (an RTL's DC spike). */
    const val CENTRE_EXCLU = 3

    /** The step centres (IF, Hz) covering the band with the oscillator [olKhz] at [fsHz], low to high, overlapping a little. */
    fun pas(olKhz: Long, fsHz: Double): List<Long> {
        val bas = (BandeLarge.DEBUT_KHZ - olKhz) * 1000.0
        val haut = bas + BandeLarge.LARGEUR_KHZ * 1000.0
        val utile = fsHz * UTILE
        val n = maxOf(1, Math.ceil((haut - bas) / (utile * 0.96)).toInt())
        val ecart = if (n == 1) 0.0 else (haut - bas - utile) / (n - 1)
        return (0 until n).map { k -> Math.round(if (n == 1) (bas + haut) / 2 else bas + utile / 2 + k * ecart) }
    }

    /** The averaged power spectrum (dB, FFT-shifted: lowest frequency first) of [n] complex samples in [iq] (I, Q interleaved). */
    fun puissanceDb(iq: FloatArray, n: Int, nfft: Int = NFFT): FloatArray {
        val acc = DoubleArray(nfft)
        val re = DoubleArray(nfft); val im = DoubleArray(nfft)
        val fenetre = DoubleArray(nfft) { 0.5 - 0.5 * cos(2 * PI * it / (nfft - 1)) }
        var blocs = 0
        var k = 0
        while (k + nfft <= n) {
            for (i in 0 until nfft) { re[i] = iq[2 * (k + i)] * fenetre[i]; im[i] = iq[2 * (k + i) + 1] * fenetre[i] }
            Fft.transform(re, im)
            for (i in 0 until nfft) acc[i] += re[i] * re[i] + im[i] * im[i]
            blocs++; k += nfft
        }
        val out = FloatArray(nfft)
        for (i in 0 until nfft) {
            val j = (i + nfft / 2) % nfft
            out[i] = (10 * Math.log10(acc[j] / maxOf(1, blocs) + 1e-20)).toFloat()
        }
        return out
    }

    /**
     * One sweep put together: each step's spectrum ([ajoute]) in its place,
     * each point taken from the step whose centre is nearest (flattest).
     */
    class Assemblage(private val olKhz: Long, private val points: Int = POINTS) {
        private val db = DoubleArray(points) { Double.NaN }
        private val distance = DoubleArray(points) { Double.MAX_VALUE }

        /** The sky frequency (kHz) at the centre of point [i]. */
        fun khz(i: Int): Double = BandeLarge.DEBUT_KHZ + BandeLarge.LARGEUR_KHZ * (i + 0.5) / points

        fun ajoute(centreFiHz: Long, fsHz: Double, spectre: FloatArray) {
            val nfft = spectre.size
            val binHz = fsHz / nfft
            val largeurPoint = BandeLarge.LARGEUR_KHZ * 1000.0 / points
            for (i in 0 until points) {
                val dHz = (khz(i) - olKhz) * 1000.0 - centreFiHz
                if (Math.abs(dHz) > fsHz * UTILE / 2) continue
                val d = Math.abs(dHz)
                if (d >= distance[i]) continue
                // The bins within this point's width (at least the nearest one), the centre's spike left out.
                val a = Math.floor((dHz - largeurPoint / 2) / binHz).toInt() + nfft / 2
                val b = Math.ceil((dHz + largeurPoint / 2) / binHz).toInt() + nfft / 2
                var somme = 0.0; var nb = 0
                for (k in maxOf(0, a)..minOf(nfft - 1, b)) {
                    if (Math.abs(k - nfft / 2) <= CENTRE_EXCLU) continue
                    somme += Math.pow(10.0, spectre[k] / 10.0); nb++
                }
                if (nb == 0) continue
                db[i] = 10 * Math.log10(somme / nb); distance[i] = d
            }
        }

        /** The sweep on the ground station's scale; the points no step covered filled from their neighbours. */
        fun valeurs(): IntArray {
            val l = db.copyOf()
            comble(l)
            val bruit = centile(l.filter { !it.isNaN() }, 0.2) ?: return IntArray(points) { BandeLarge.BRUIT }
            return IntArray(points) { i ->
                val x = if (l[i].isNaN()) bruit else l[i]
                Math.round(BandeLarge.BRUIT + (x - bruit) * BandeLarge.UNITES_PAR_DB).toInt().coerceIn(0, 65_535)
            }
        }

        /** How much of the band was measured (0 to 1). */
        fun couverture(): Double = db.count { !it.isNaN() }.toDouble() / points
    }

    private fun comble(l: DoubleArray) {
        var i = 0
        while (i < l.size) {
            if (!l[i].isNaN()) { i++; continue }
            var j = i; while (j < l.size && l[j].isNaN()) j++
            val g = if (i > 0) l[i - 1] else Double.NaN
            val d = if (j < l.size) l[j] else Double.NaN
            for (k in i until j) l[k] = when {
                g.isNaN() -> d
                d.isNaN() -> g
                else -> g + (d - g) * (k - i + 1) / (j - i + 1)
            }
            i = j
        }
    }

    /** The [p]-th quantile of [l], null when empty. */
    fun centile(l: List<Double>, p: Double): Double? = if (l.isEmpty()) null else l.sorted()[((l.size - 1) * p).toInt()]

    /**
     * Whether the SDR looks at the MiniTiouner's LNB: its sweep should show
     * the beacon. Null when all is coherent, else what to check — no beacon
     * at all, or the beacon far from where the MiniTiouner finds it
     * ([cielBaliseMiniKhz], 0 when it is not on it).
     */
    fun avertissement(signaux: List<SignalLarge>, cielBaliseMiniKhz: Long): String? {
        val b = signaux.firstOrNull { it.balise }
            ?: return "pas de balise dans le spectre du SDR : est-il sur un répartiteur de la même tête (LNB) que le MiniTiouner, l'OL du LNB est-il juste, son gain suffit-il ?"
        if (cielBaliseMiniKhz > 0 && Math.abs(b.centreKhz - cielBaliseMiniKhz) > 150)
            return "la balise tombe à " + String.format(java.util.Locale.FRANCE, "%.3f", b.centreKhz / 1000.0) + " MHz pour le SDR et " +
                String.format(java.util.Locale.FRANCE, "%.3f", cielBaliseMiniKhz / 1000.0) + " MHz pour le MiniTiouner : correction (ppm) du SDR à régler, ou pas la même tête"
        return null
    }

    /**
     * One sweep read from [src] (opened, its rate set to [fsHz]) with the
     * oscillator [olKhz]: each step tuned, [vidageMs] of samples thrown away
     * (what was on its way), then [MOYENNES] FFTs. Null when [continuer]
     * turns false or the source is gone.
     */
    fun balaie(src: SourceIq, fsHz: Double, olKhz: Long, continuer: () -> Boolean, vidageMs: Long = 120L,
               horloge: () -> Long = System::currentTimeMillis): Assemblage? {
        val a = Assemblage(olKhz)
        val voulu = NFFT * MOYENNES
        val iq = FloatArray(2 * voulu)
        val octets = ByteArray(16_384)
        val courts = if (src.iq16) ShortArray(16_384) else null
        for (c in pas(olKhz, fsHz)) {
            if (!continuer()) return null
            src.setCenterFreq(c)
            runCatching { src.resetBuffer() }
            val fin = horloge() + vidageMs
            var n = 0
            var vides = 0
            while (n < voulu) {
                if (!continuer()) return null
                val lu = if (courts != null) src.read16(courts, 500) else src.read(octets, 500)
                if (lu < 0) return null
                if (lu == 0) { if (++vides > 10) return null; continue }
                if (horloge() < fin) continue
                val paires = lu / 2
                for (p in 0 until paires) {
                    if (n >= voulu) break
                    if (courts != null) { iq[2 * n] = courts[2 * p] / 32768f; iq[2 * n + 1] = courts[2 * p + 1] / 32768f }
                    else { iq[2 * n] = ((octets[2 * p].toInt() and 0xFF) - 127.5f) / 128f; iq[2 * n + 1] = ((octets[2 * p + 1].toInt() and 0xFF) - 127.5f) / 128f }
                    n++
                }
            }
            a.ajoute(c, fsHz, puissanceDb(iq, n))
        }
        return a
    }
}

/**
 * A test source for [SpectreLocal]: QO-100's wide band as an LNB with the
 * oscillator [olKhz] would give it — the beacon and a few stations as
 * shaped noise over the noise floor, made a block at a time in the
 * frequency domain. Receives nothing: the demonstration, the tests.
 */
class GenerateurBandeLarge(private val olKhz: Long, private val fsHz: Double = 2_400_000.0, graine: Long = 7L) : SourceIq {
    /** A signal: sky frequency (kHz), symbol rate (kS/s), height above the noise (dB). */
    data class Signal(val khz: Long, val srKs: Int, val db: Double)

    @Volatile var signaux = listOf(Signal(Datv.BALISE_KHZ, 1500, 8.3), Signal(10_494_750L, 1000, 6.0),
        Signal(10_497_250L, 333, 7.0), Signal(10_498_750L, 125, 9.0))
    @Volatile private var centreHz = 0L
    private val alea = java.util.Random(graine)
    private val n = 2048
    private val re = DoubleArray(n); private val im = DoubleArray(n)
    private var bloc = ByteArray(0)
    private var pos = 0

    override fun setSampleRate(rate: Int): Double = fsHz
    override fun setFreqCorrection(ppm: Int) {}
    override fun setCenterFreq(hz: Long): Long { centreHz = hz; pos = bloc.size; return hz }
    override fun setGain(tenthDb: Int?) {}
    override fun setAgc(on: Boolean) {}

    private fun nouveau() {
        val binHz = fsHz / n
        for (i in 0 until n) {
            val f = if (i < n / 2) i * binHz else (i - n) * binHz
            val cielKhz = (centreHz + f) / 1000.0 + olKhz
            var p = 1.0
            for (s in signaux) {
                // A raised cosine (roll-off 0.35), as a DVB-S2 signal's spectrum.
                val x = Math.abs(cielKhz - s.khz) / s.srKs
                val forme = when { x <= 0.325 -> 1.0; x >= 0.675 -> 0.0; else -> 0.5 * (1 + cos(PI / 0.35 * (x - 0.325))) }
                p += Math.pow(10.0, s.db / 10) * forme
            }
            // Complex Gaussian with that power (Box-Muller).
            val a = sqrt(-2 * ln(1 - alea.nextDouble())) * sqrt(p / 2); val ph = 2 * PI * alea.nextDouble()
            re[i] = a * cos(ph); im[i] = a * sin(ph)
        }
        // The inverse FFT by the conjugate.
        for (i in 0 until n) im[i] = -im[i]
        Fft.transform(re, im)
        bloc = ByteArray(2 * n) { k ->
            val v = if (k % 2 == 0) re[k / 2] else -im[k / 2]
            (127.5 + v / n * 12.0 * Math.sqrt(n.toDouble())).coerceIn(0.0, 255.0).toInt().toByte()
        }
        pos = 0
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        var k = 0
        while (k < buf.size - 1) {
            if (pos >= bloc.size) nouveau()
            val m = minOf(buf.size - k, bloc.size - pos) and 1.inv()
            System.arraycopy(bloc, pos, buf, k, m); k += m; pos += m
        }
        return k
    }

    override fun close() {}
}
