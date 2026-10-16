/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.cw.DecodeurCw
import fr.f4ioz.satcombo.sdr.Dsp
import fr.f4ioz.satcombo.sstv.SstvEncoder
import fr.f4ioz.satcombo.sstv.SstvMode
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthetic transponder IQ for the classifier's tests: noise and stations
 * of every kind, with drift.
 *
 * At a sixteenth of the receiver's rate and a sixteenth of its FFT: the same
 * 16.15 Hz bins and 62 ms frames, so the analyser sees exactly what it sees
 * on the air, sixteen times faster to make.
 */
class ScenesQo100(val dureeS: Double, seed: Long, val t0Ms: Long = 1_792_000_000_000L) {

    val n = (dureeS * CADENCE).toInt()
    val i = FloatArray(n)
    val q = FloatArray(n)
    val rnd = Random(seed)
    /** Complex noise, power 2·sigma² over the whole rate. */
    val sigma = 0.01
    private val n0 = 2 * sigma * sigma / CADENCE

    init { for (k in 0 until n) { i[k] = (sigma * rnd.nextGaussian()).toFloat(); q[k] = (sigma * rnd.nextGaussian()).toFloat() } }

    /** Amplitude of a signal [snrDb] over the noise in 2.5 kHz. */
    fun amplitude(snrDb: Double) = sqrt(10.0.pow(snrDb / 10) * n0 * 2500)

    /** Slow drift: a slope and a wobble (Hz at time t s). */
    fun derive(pente: Double = rnd.nextGaussian() * 0.08, wob: Double = 2.0): (Double) -> Double {
        val ph = rnd.nextDouble() * 2 * PI
        return { t -> pente * t + wob * sin(2 * PI * t / 40 + ph) }
    }

    /** A tone of frequency [f] (Hz, function of t) and envelope [a] (function of t), added. */
    private fun ajouteTon(f: (Double) -> Double, a: (Double) -> Double, de: Int = 0, a_: Int = n) {
        var ph = rnd.nextDouble() * 2 * PI
        for (k in de until a_) {
            val t = k / CADENCE
            val amp = a(t)
            ph += 2 * PI * f(t) / CADENCE
            ph = tour(ph)
            if (amp == 0.0) continue
            i[k] += (amp * cos(ph)).toFloat(); q[k] += (amp * sin(ph)).toFloat()
        }
    }

    fun porteuse(hz: Double, snrDb: Double) {
        val d = derive()
        val a = amplitude(snrDb)
        ajouteTon({ hz + d(it) }, { a })
    }

    /**
     * CW: [texte] again and again at [wpm], a pause between calls ([pauseS]
     * at least). [fronteS]: the keying edges (4 ms as a keyer shapes them;
     * near nothing, a hard key and its clicks). [qsbDb]: a slow fade of that
     * depth. [copies]: other lines keyed with it (offset Hz, level dB
     * relative), as a transmitter's spurious ones.
     */
    fun cw(hz: Double, snrDb: Double, wpm: Int = 18, texte: String = "CQ CQ DE F4IOZ F4IOZ K", fronteS: Double = 0.004,
           qsbDb: Double = 0.0, pauseS: Double = 1.0, copies: List<Pair<Double, Double>> = emptyList()) {
        val d = derive()
        val a = amplitude(snrDb)
        val cle = BooleanArray(n)
        var t = rnd.nextDouble() * 1.5
        while (t < dureeS) {
            for ((bas, ms) in DecodeurCw.manipulation(texte, wpm)) {
                val k0 = (t * CADENCE).toInt(); val k1 = ((t + ms / 1000) * CADENCE).toInt().coerceAtMost(n)
                if (bas) for (k in k0.coerceAtMost(n) until k1) cle[k] = true
                t += ms / 1000
            }
            t += pauseS + rnd.nextDouble() * 2
        }
        val env = lisse(cle, fronteS)
        val phQsb = rnd.nextDouble() * 2 * PI
        val qsb: (Double) -> Double = { t -> 10.0.pow(-qsbDb / 20 * (0.5 + 0.5 * sin(2 * PI * t / 9 + phQsb))) }
        for ((dHz, dDb) in listOf(0.0 to 0.0) + copies) {
            val g = 10.0.pow(dDb / 20)
            ajouteTon({ hz + dHz + d(it) }, { a * g * qsb(it) * env[(it * CADENCE).toInt().coerceIn(0, n - 1)] })
        }
    }

    /**
     * A comb of steady weak lines every [pasHz] from [deHz] to [aHz], each
     * [snrMinDb]..[snrMaxDb] over the noise in 2.5 kHz and slowly
     * scintillating: what mains-rate interference leaves in a receiver chain.
     */
    fun peigne(deHz: Double, aHz: Double, pasHz: Double, snrMinDb: Double, snrMaxDb: Double) {
        val k0 = Math.ceil(deHz / pasHz).toInt(); val k1 = Math.floor(aHz / pasHz).toInt()
        val raies = (k0..k1).map { k -> Triple(k * pasHz, amplitude(snrMinDb + rnd.nextDouble() * (snrMaxDb - snrMinDb)), rnd.nextDouble() * 2 * PI) }
        // Each line a rotating phasor (renormalised now and then), its level updated per 256 samples.
        val cr = DoubleArray(raies.size); val ci = DoubleArray(raies.size)
        val wr = DoubleArray(raies.size); val wi = DoubleArray(raies.size)
        for (j in raies.indices) {
            val p0 = rnd.nextDouble() * 2 * PI; cr[j] = cos(p0); ci[j] = sin(p0)
            val w = 2 * PI * raies[j].first / CADENCE; wr[j] = cos(w); wi[j] = sin(w)
        }
        val amp = DoubleArray(raies.size)
        for (k in 0 until n) {
            if (k % 256 == 0) {
                val t = k / CADENCE
                for (j in raies.indices) {
                    amp[j] = raies[j].second * (1 + 0.4 * sin(2 * PI * t / 5 + raies[j].third))
                    val m = sqrt(cr[j] * cr[j] + ci[j] * ci[j]); cr[j] /= m; ci[j] /= m
                }
            }
            var re = 0.0; var im = 0.0
            for (j in raies.indices) {
                val r = cr[j] * wr[j] - ci[j] * wi[j]; ci[j] = cr[j] * wi[j] + ci[j] * wr[j]; cr[j] = r
                re += amp[j] * r; im += amp[j] * ci[j]
            }
            i[k] += re.toFloat(); q[k] += im.toFloat()
        }
    }

    /** FT8 or FT4: a station sending every other slot, its frame 0.5 s into it (plus the satellite's quarter second). */
    fun ft(hz: Double, snrDb: Double, mode: Ft8Signal.Mode, parite: Int = rnd.nextInt(2), chaqueCreneau: Boolean = false) {
        val periode = if (mode.tons == 8) 15.0 else 7.5
        val d = derive()
        val a = amplitude(snrDb)
        val freq = DoubleArray(n) { Double.NaN }
        // The slots on UTC: t0Ms is on a 15 s boundary plus this.
        val decal = (t0Ms % 15_000L) / 1000.0
        var s = Math.floor(decal / periode).toInt()
        while (true) {
            val debut = s * periode - decal + 0.5 + 0.25 + rnd.nextGaussian() * 0.15
            if (debut >= dureeS) break
            if (chaqueCreneau || s % 2 == parite) {
                val tons = IntArray(mode.symboles) { rnd.nextInt(mode.tons) }
                for ((p, v) in mode.synchro) tons[p] = v
                val base = hz + rnd.nextGaussian() * 2
                val tr = Ft8Signal.trajectoire(tons, mode, 0.0)
                for (j in tr.indices step 1) {
                    val t = debut + j / mode.cadenceHz
                    val k = (t * CADENCE).toInt()
                    if (k in 0 until n) freq[k] = base + tr[j]
                }
                // Fill the samples between the trajectory's (it is at 12.8 kHz).
                var dernier = Double.NaN
                val k0 = (debut * CADENCE).toInt().coerceAtLeast(0); val k1 = ((debut + mode.dureeS) * CADENCE).toInt().coerceAtMost(n)
                for (k in k0 until k1) { if (freq[k].isNaN()) freq[k] = dernier else dernier = freq[k] }
            }
            s++
        }
        ajouteTon({ t -> val k = (t * CADENCE).toInt().coerceIn(0, n - 1); if (freq[k].isNaN()) 0.0 else freq[k] + d(t) },
            { t -> val k = (t * CADENCE).toInt().coerceIn(0, n - 1); if (freq[k].isNaN()) 0.0 else a })
    }

    /** BPSK at [baud] with a cosine through each reversal (PSK31; the middle beacon at 400). [ralenti]: idle, reversals only. */
    fun bpsk(hz: Double, snrDb: Double, baud: Double = 31.25, ralenti: Double = 0.3) {
        val d = derive()
        val a = amplitude(snrDb)
        val nSym = (dureeS * baud).toInt() + 2
        // Varicode-like: characters of 1s and 0s split by "00"; idle stretches all 0.
        val inv = BooleanArray(nSym)
        var k = 0
        while (k < nSym) {
            if (rnd.nextDouble() < ralenti) { repeat(20) { if (k < nSym) inv[k++] = true } }
            else {
                val l = 1 + rnd.nextInt(9)
                repeat(l) { if (k < nSym) inv[k++] = rnd.nextBoolean() && it > 0 && it < l - 1 }
                repeat(2) { if (k < nSym) inv[k++] = true }
            }
        }
        val signe = IntArray(nSym)
        var sg = 1
        for (j in 0 until nSym) { signe[j] = sg; if (inv[j]) sg = -sg }
        ajouteTon({ hz + d(it) }, { t ->
            val x = t * baud; val j = x.toInt().coerceAtMost(nSym - 1); val f = x - j
            // Over a reversal symbol the amplitude goes through zero as a cosine.
            a * (if (inv[j]) signe[j] * cos(PI * f) else signe[j].toDouble())
        })
    }

    /** RTTY 45.45 baud, 170 Hz shift, continuous phase: start, five bits, one and a half stops. */
    fun rtty(hz: Double, snrDb: Double) {
        val d = derive()
        val a = amplitude(snrDb)
        val bitS = 1 / 45.45
        val marque = BooleanArray(n) { true }
        var t = 0.3
        while (t < dureeS) {
            val bits = listOf(false) + List(5) { rnd.nextBoolean() } + listOf(true)
            for ((x, b) in bits.withIndex()) {
                val dur = if (x == 6) 1.5 * bitS else bitS
                val k0 = (t * CADENCE).toInt(); val k1 = ((t + dur) * CADENCE).toInt().coerceAtMost(n)
                for (k in k0.coerceAtMost(n) until k1) marque[k] = b
                t += dur
            }
        }
        ajouteTon({ t -> hz + d(t) + if (marque[(t * CADENCE).toInt().coerceIn(0, n - 1)]) -85.0 else 85.0 }, { a })
    }

    /** An SSTV picture after another (Martin M1 by default), the suppressed carrier at [hz]. */
    fun sstv(hz: Double, snrDb: Double, mode: SstvMode = SstvMode.ALL.first { it.name == "Martin M1" }) {
        val img = IntArray(mode.width * mode.height) { k -> val x = k % mode.width; val y = k / mode.width; (0xff shl 24) or ((x * 255 / mode.width) shl 16) or ((y * 255 / mode.height) shl 8) or ((x xor y) and 0xff) }
        val ra = AUDIO
        val audio = FloatArray((dureeS * ra).toInt() + 1)
        var k = 0
        while (k < audio.size) {
            val pcm = SstvEncoder.encode(mode, img, ra)
            for (s in pcm) { if (k >= audio.size) break; audio[k++] = s / 26000f }
            k += ra / 2
        }
        ajouteAudioBlu(hz, audio, amplitude(snrDb) * sqrt(2.0))
    }

    /** Wide digital voice: 16 carriers 75 Hz apart, QPSK at 50 baud, sent in overs. */
    fun ofdm(hz: Double, snrDb: Double, porteuses: Int = 16) {
        val d = derive()
        val a = amplitude(snrDb) / sqrt(porteuses.toDouble())
        val actif = overs(20.0, 40.0, 2.0, 6.0)
        val ph = DoubleArray(porteuses)
        val nSym = (dureeS * 50).toInt() + 1
        val sym = Array(porteuses) { DoubleArray(nSym) { rnd.nextInt(4) * PI / 2 + PI / 4 } }
        val haut = DoubleArray(n); val bas = DoubleArray(n)
        for (c in 0 until porteuses) {
            val f = hz + (c - (porteuses - 1) / 2.0) * 75.0
            for (k in 0 until n) {
                val t = k / CADENCE
                ph[c] = tour(ph[c] + 2 * PI * (f + d(t)) / CADENCE)
                if (!actif[k]) continue
                val p = ph[c] + sym[c][(t * 50).toInt()]
                haut[k] += a * cos(p); bas[k] += a * sin(p)
            }
        }
        for (k in 0 until n) { i[k] += haut[k].toFloat(); q[k] += bas[k].toFloat() }
    }

    /**
     * A voice in USB, its suppressed carrier at [hz]: a pitch's harmonics
     * shaped by two moving formants, in syllables, phrases and overs.
     */
    fun blu(hz: Double, snrDb: Double) {
        val d = derive()
        val a = amplitude(snrDb)
        val actif = overs(15.0, 40.0, 3.0, 8.0)
        // Syllables: 120-300 ms on, 40-150 ms off, a breath now and then.
        val syl = BooleanArray(n)
        var t = 0.0
        while (t < dureeS) {
            val on = 0.12 + rnd.nextDouble() * 0.18
            val k0 = (t * CADENCE).toInt(); val k1 = ((t + on) * CADENCE).toInt().coerceAtMost(n)
            for (k in k0.coerceAtMost(n) until k1) syl[k] = true
            t += on + 0.04 + rnd.nextDouble() * 0.11 + if (rnd.nextDouble() < 0.12) 0.3 + rnd.nextDouble() * 0.5 else 0.0
        }
        val env = lisse(syl, 0.03)
        var phase0 = 0.0
        var f0 = 120.0; var cible = 120.0
        var fa = 600.0; var fb = 1600.0
        val gen = DoubleArray(2)
        val gains = DoubleArray(31)
        var norme = 1.0
        var pc = 0.0
        for (k in 0 until n) {
            val tt = k / CADENCE
            if (k % 2000 == 0) { cible = 100.0 + rnd.nextDouble() * 90; fa = 400.0 + rnd.nextDouble() * 500; fb = 1100.0 + rnd.nextDouble() * 1200 }
            f0 += (cible - f0) * 0.0005
            phase0 += 2 * PI * f0 / CADENCE
            if (phase0 > PI) phase0 -= 2 * PI
            if (k % 64 == 0) {
                norme = 0.0
                for (h in 1 until gains.size) {
                    val fh = h * f0
                    gains[h] = if (fh in 300.0..3000.0) exp(-((fh - fa) / 250).pow(2)) + 0.5 * exp(-((fh - fb) / 350).pow(2)) + 0.03 else 0.0
                    norme += gains[h] * gains[h]
                }
            }
            pc += 2 * PI * (hz + d(tt)) / CADENCE
            pc = tour(pc)
            val e = env[k] * if (actif[k]) 1.0 else 0.0
            if (e == 0.0) continue
            var re = 0.0; var im = 0.0
            val c0 = cos(phase0); val s0 = sin(phase0)
            var cr = 1.0; var ci = 0.0
            for (h in 1 until gains.size) {
                val nr = cr * c0 - ci * s0; ci = cr * s0 + ci * c0; cr = nr
                val g = gains[h]
                if (g != 0.0) { re += g * cr; im += g * ci }
            }
            val s = a * e / sqrt(norme.coerceAtLeast(1e-9))
            // The carrier offset and drift: one rotation for all harmonics.
            gen[0] = re * s; gen[1] = im * s
            val c = cos(pc); val sn = sin(pc)
            i[k] += (gen[0] * c - gen[1] * sn).toFloat(); q[k] += (gen[0] * sn + gen[1] * c).toFloat()
        }
    }

    /** More noise from [deHz] to [aHz] ([gainDb] over the floor): the transponder's passband edge, a tuner's slope. */
    fun marche(deHz: Double, aHz: Double, gainDb: Double) {
        val lp = Dsp.lowPass(127, (aHz - deHz) / 2, CADENCE)
        val c = (aHz + deHz) / 2
        val s = sigma * sqrt(10.0.pow(gainDb / 10) - 1) / sqrt(lp.sumOf { (it * it).toDouble() } * CADENCE / (aHz - deHz))
        val bi = DoubleArray(n) { rnd.nextGaussian() * s }; val bq = DoubleArray(n) { rnd.nextGaussian() * s }
        for (k in 0 until n) {
            var r = 0.0; var m = 0.0
            for (j in lp.indices) { val x = k - j; if (x < 0) break; r += lp[j] * bi[x]; m += lp[j] * bq[x] }
            val ph = 2 * PI * c * k / CADENCE
            i[k] += (r * cos(ph) - m * sin(ph)).toFloat(); q[k] += (r * sin(ph) + m * cos(ph)).toFloat()
        }
    }

    /** Overs of [min]..[max] s, [pmin]..[pmax] s apart. */
    private fun overs(min: Double, max: Double, pmin: Double, pmax: Double): BooleanArray {
        val o = BooleanArray(n)
        var t = rnd.nextDouble() * 2
        while (t < dureeS) {
            val l = min + rnd.nextDouble() * (max - min)
            for (k in (t * CADENCE).toInt().coerceAtMost(n) until ((t + l) * CADENCE).toInt().coerceAtMost(n)) o[k] = true
            t += l + pmin + rnd.nextDouble() * (pmax - pmin)
        }
        return o
    }

    /** A real sound at [AUDIO] S/s (one in USB) put [hz] above in the IQ: its positive frequencies only. */
    fun ajouteAudioBlu(hz: Double, audio: FloatArray, gain: Double) {
        // Complex band-pass 150..3150 Hz: the low-pass turned up by 1650 Hz.
        val ra = AUDIO.toDouble()
        val lp = Dsp.lowPass(127, 1500.0, ra)
        val l = lp.size
        val hr = DoubleArray(l) { lp[it] * cos(2 * PI * 1650.0 * it / ra) }
        val hi = DoubleArray(l) { lp[it] * sin(2 * PI * 1650.0 * it / ra) }
        val ar = FloatArray(audio.size); val ai = FloatArray(audio.size)
        for (k in audio.indices) {
            var r = 0.0; var m = 0.0
            for (j in 0 until l) { val x = k - j; if (x < 0) break; val v = audio[x]; r += hr[j] * v; m += hi[j] * v }
            ar[k] = r.toFloat(); ai[k] = m.toFloat()
        }
        val br = Ft8Signal.reechantillonne(ar, ra, CADENCE)
        val bi = Ft8Signal.reechantillonne(ai, ra, CADENCE)
        val d = derive()
        var ph = 0.0
        for (k in 0 until minOf(n, br.size)) {
            ph = tour(ph + 2 * PI * (hz + d(k / CADENCE)) / CADENCE)
            val r = br[k].toDouble(); val m = bi[k].toDouble()
            if (r == 0.0 && m == 0.0) continue
            val c = cos(ph); val s = sin(ph)
            i[k] += (gain * (r * c - m * s)).toFloat(); q[k] += (gain * (r * s + m * c)).toFloat()
        }
    }

    private fun tour(x: Double) = x - 2 * PI * Math.floor((x + PI) / (2 * PI))

    private fun lisse(b: BooleanArray, s: Double): DoubleArray {
        val w = (s * CADENCE).toInt().coerceAtLeast(1)
        val out = DoubleArray(n)
        var acc = 0
        for (k in 0 until n) {
            if (b[k]) acc++
            if (k >= w && b[k - w]) acc--
            out[k] = 0.5 - 0.5 * cos(PI * acc / w)
        }
        return out
    }

    /** Fed to [a] in the receiver's blocks, the clock starting at [t0Ms]. */
    fun analyse(a: AnalyseurQo100, bloc: Int = 512) {
        var k = 0
        while (k < n) {
            val c = minOf(bloc, n - k)
            a.pousse(i.copyOfRange(k, k + c), q.copyOfRange(k, k + c), c, t0Ms + ((k + c) * 1000.0 / CADENCE).toLong())
            k += c
        }
    }

    val finMs get() = t0Ms + (n * 1000.0 / CADENCE).toLong()

    companion object {
        /** A sixteenth of the receiver's rate. */
        const val CADENCE = Dsp.RTL_RATE / 16.0
        const val TAILLE = 4096
        /** The sounds made as audio first (SSTV): a sixth of [CADENCE]. */
        const val AUDIO = 11_025
    }
}
