/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import fr.f4ioz.satcombo.cw.DecodeurCw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class FiPosteTest {

    /** The rig's IF as sampled at [fs]: a tone at 12 kHz + [delta] (the dial + delta), [s] seconds. */
    private fun ton(fs: Int, delta: Double, s: Double, niveau: Double = 0.5): ShortArray =
        ShortArray((fs * s).toInt()) { (niveau * 32767 * cos(2 * PI * (FiPoste.CENTRE_HZ + delta) * it / fs)).toInt().toShort() }

    /** Through the converter: the complex baseband, the first 50 ms (the filter filling) left out. */
    private fun base(fs: Int, x: ShortArray, inverse: Boolean = false): Pair<FloatArray, FloatArray> {
        val c = ConvertisseurFi(fs, inverse)
        val i = FloatArray(c.maxSortie(x.size)); val q = FloatArray(c.maxSortie(x.size))
        var n = 0
        var p = 0
        val oi = FloatArray(1024); val oq = FloatArray(1024)
        while (p < x.size) {
            val k = minOf(1000, x.size - p)
            val m = c.traite(x.copyOfRange(p, p + k), k, oi, oq)
            System.arraycopy(oi, 0, i, n, m); System.arraycopy(oq, 0, q, n, m)
            n += m; p += k
        }
        val saute = c.debitSortie / 20
        return i.copyOfRange(saute, n) to q.copyOfRange(saute, n)
    }

    /** Magnitude of the line at [f] Hz (signed) in complex [i], [q] at [rate]. */
    private fun raie(i: FloatArray, q: FloatArray, f: Double, rate: Int): Double {
        var re = 0.0; var im = 0.0
        for (n in i.indices) {
            val a = -2 * PI * f * n / rate
            val c = cos(a); val s = sin(a)
            re += i[n] * c - q[n] * s; im += i[n] * s + q[n] * c
        }
        return hypot(re, im) / i.size
    }

    /** Mean frequency (Hz, signed) of a complex tone, from the phase steps. */
    private fun frequence(i: FloatArray, q: FloatArray, rate: Int): Double {
        var re = 0.0; var im = 0.0
        for (n in 1 until i.size) { re += i[n] * i[n - 1] + q[n] * q[n - 1]; im += q[n] * i[n - 1] - i[n] * q[n - 1] }
        return Math.atan2(im, re) * rate / (2 * PI)
    }

    private fun db(x: Double) = 20 * log10(x)

    @Test fun `les plans du reseau et de la carte USB`() {
        val r = FiPoste.plan(16_000)!!
        assertEquals(4_000.0, r.repliHz, 0.0); assertTrue(r.inverse); assertEquals(3_600.0, r.demiUtileHz, 0.1); assertEquals(8_000, r.debitBase)
        val u = FiPoste.plan(48_000)!!
        assertEquals(12_000.0, u.repliHz, 0.0); assertTrue(!u.inverse); assertEquals(7_500.0, u.demiUtileHz, 0.1); assertEquals(24_000, u.debitBase)
        // 8 kHz: the centre folds onto Nyquist, nothing usable.
        assertNull(FiPoste.plan(8_000))
        assertTrue(FiPoste.plan(44_100)!!.demiUtileHz >= 7_000.0)
    }

    @Test fun `reseau 16 kHz - la FI repliee et retournee, le cadran plus delta sort a plus delta`() {
        for (d in listOf(-3_200.0, -1_500.0, 0.0, 700.0, 2_000.0, 3_300.0)) {
            val (i, q) = base(16_000, ton(16_000, d, 0.5))
            val f = frequence(i, q, 8_000)
            assertEquals("Δ = $d", d, f, 2.0)
            // Its level kept, the mirror line (the wrong sign) gone.
            assertEquals("niveau à Δ = $d", 0.5, raie(i, q, d, 8_000), 0.06)
            if (d != 0.0) assertTrue("miroir à Δ = $d", db(raie(i, q, -d, 8_000) / 0.5) < -50)
        }
    }

    @Test fun `carte USB 48 kHz - toute la FI, sans retournement`() {
        for (d in listOf(-7_000.0, -2_500.0, 1_000.0, 5_000.0, 7_200.0)) {
            val (i, q) = base(48_000, ton(48_000, d, 0.3))
            assertEquals("Δ = $d", d, frequence(i, q, 24_000), 2.0)
            assertEquals(0.5, raie(i, q, d, 24_000), 0.05)
            assertTrue(db(raie(i, q, -d, 24_000) / 0.5) < -50)
        }
    }

    @Test fun `le sens peut etre retourne une fois de plus`() {
        val (i, q) = base(16_000, ton(16_000, 1_200.0, 0.4), inverse = true)
        assertEquals(-1_200.0, frequence(i, q, 8_000), 2.0)
    }

    @Test fun `bords - hors de la FI coupe, au reseau rien ne sort au-dela de 4 kHz`() {
        // USB: 25 kHz at 48 kHz (dial + 13 kHz), outside the IF: cut by more than 50 dB.
        val (i, q) = base(48_000, ton(48_000, 13_000.0, 0.5))
        var e = 0.0; for (n in i.indices) e += (i[n] * i[n] + q[n] * q[n]).toDouble()
        assertTrue("hors FI " + db(sqrt(e / i.size) / 0.5), db(sqrt(e / i.size) / 0.5) < -50)
        // Network: the half-span flat within 1 dB up to ±3.4 kHz. At the folding edge (±4 kHz: 8 kHz
        // and DC of the 16 kHz stream) both lines of the real tone fall on one (full scale 1 for a
        // 0.5 tone): the filter's edge halves it, and nothing beyond is told apart from its mirror.
        for (d in listOf(-3_400.0, 3_400.0)) {
            val (a, b) = base(16_000, ton(16_000, d, 0.5))
            assertTrue("Δ = $d : " + db(raie(a, b, d, 8_000) / 0.5), abs(db(raie(a, b, d, 8_000) / 0.5)) < 1.0)
        }
        for (d in listOf(-4_000.0, 4_000.0)) {
            val (a, b) = base(16_000, ton(16_000, d, 0.5))
            var p = 0.0; for (n in a.indices) p += (a[n] * a[n] + b[n] * b[n]).toDouble()
            assertTrue("Δ = $d : " + db(sqrt(p / a.size)), db(sqrt(p / a.size)) < -5.5)
        }
    }

    @Test fun `le reechantillonneur garde la frequence et le niveau`() {
        val r = ReechantillonneurComplexe(8_000, 32_000, 4_000.0, 800.0)
        val n = 4_000
        val i = FloatArray(n) { cos(2 * PI * 1_234.0 * it / 8_000).toFloat() }
        val q = FloatArray(n) { sin(2 * PI * 1_234.0 * it / 8_000).toFloat() }
        val oi = FloatArray(r.maxSortie(n)); val oq = FloatArray(r.maxSortie(n))
        val m = r.traite(i, q, n, oi, oq)
        assertEquals(4.0 * n, m.toDouble(), 2.0)
        val a = oi.copyOfRange(2_000, m); val b = oq.copyOfRange(2_000, m)
        assertEquals(1_234.0, frequence(a, b, 32_000), 1.0)
        assertEquals(1.0, raie(a, b, 1_234.0, 32_000), 0.02)
    }

    /** The source fed in blocks as the transport would, everything read back at the dongle rate. */
    private fun parSource(fs: Int, x: ShortArray, bloc: Int): ShortArray {
        val s = SourceFiPoste(fs, if (fs == 16_000) "reseau" else "usb")
        val buf = ShortArray(RtlTcp.BLOC)
        val out = ArrayList<ShortArray>()
        var p = 0
        while (p < x.size) {
            val k = minOf(bloc, x.size - p)
            s.pousse(x.copyOfRange(p, p + k), k)
            p += k
            while (true) { val n = s.read16(buf, 0); if (n <= 0) break; out += buf.copyOf(n) }
        }
        s.close()
        val r = ShortArray(out.sumOf { it.size })
        var o = 0
        for (a in out) { System.arraycopy(a, 0, r, o, a.size); o += a.size }
        return r
    }

    /** The source's I/Q through [RxChain] as SdrPc runs it: the 44.1 kHz sound. */
    private fun demodule(iq: ShortArray, mode: RxMode, offset: Double, bw: Double = 0.0): ShortArray {
        val c = RxChain(); c.mode = mode; c.offsetHz = offset; c.bandwidthHz = bw
        val pcm = ShortArray(c.maxAudio(RtlTcp.BLOC))
        val res = ShortArray(iq.size / 40 + 64)
        var o = 0
        var p = 0
        while (p < iq.size) {
            val k = minOf(RtlTcp.BLOC, iq.size - p)
            val n = c.process16(iq.copyOfRange(p, p + k), k, pcm)
            System.arraycopy(pcm, 0, res, o, n); o += n
            p += k
        }
        return res.copyOf(o)
    }

    /** The strongest audio tone between 200 and 4000 Hz (10 Hz steps). */
    private fun tonAudio(a: ShortArray, rate: Int = Dsp.AUDIO_RATE): Double {
        val x = a.copyOfRange(maxOf(a.size / 3, a.size - 8_192), a.size)
        var best = 0.0; var bestP = -1.0
        var f = 200.0
        while (f <= 4_000.0) {
            var re = 0.0; var im = 0.0
            for (n in x.indices) { val ph = 2 * PI * f * n / rate; re += x[n] * cos(ph); im += x[n] * sin(ph) }
            val p = re * re + im * im
            if (p > bestP) { bestP = p; best = f }
            f += 10.0
        }
        return best
    }

    @Test fun `la source dans la chaine - BLU des deux cotes, par le reseau et par la carte`() {
        for (fs in listOf(16_000, 48_000)) {
            val bloc = if (fs == 16_000) 320 else 960
            // A carrier 1.5 kHz above the dial, listened to on the dial in USB: a 1.5 kHz note.
            val usb = demodule(parSource(fs, ton(fs, 1_500.0, 1.0, 0.3), bloc), RxMode.USB, 0.0)
            assertEquals("USB $fs", 1_500.0, tonAudio(usb), 20.0)
            // 1 kHz below the dial, in LSB: a 1 kHz note.
            val lsb = demodule(parSource(fs, ton(fs, -1_000.0, 1.0, 0.3), bloc), RxMode.LSB, 0.0)
            assertEquals("LSB $fs", 1_000.0, tonAudio(lsb), 20.0)
            // The offset tunes within the IF: the same carrier at +2.5 kHz, listened at +1.7 kHz.
            val dec = demodule(parSource(fs, ton(fs, 2_500.0, 1.0, 0.3), bloc), RxMode.USB, 1_700.0)
            assertEquals("décalage $fs", 800.0, tonAudio(dec), 20.0)
        }
    }

    @Test fun `FM et AM par la carte USB`() {
        val fs = 48_000
        // NFM, ±2.5 kHz deviation of a 1 kHz tone, 2 kHz above the dial.
        var ph = 0.0
        val fm = ShortArray(fs) {
            ph += 2 * PI * (FiPoste.CENTRE_HZ + 2_000.0 + 2_500.0 * sin(2 * PI * 1_000.0 * it / fs)) / fs
            (0.3 * 32767 * cos(ph)).toInt().toShort()
        }
        assertEquals(1_000.0, tonAudio(demodule(parSource(fs, fm, 960), RxMode.NFM, 2_000.0, 12_000.0)), 20.0)
        // AM, 600 Hz at 50 %, on the dial.
        val am = ShortArray(fs) { (0.3 * 32767 * (1 + 0.5 * sin(2 * PI * 600.0 * it / fs)) * cos(2 * PI * FiPoste.CENTRE_HZ * it / fs)).toInt().toShort() }
        assertEquals(600.0, tonAudio(demodule(parSource(fs, am, 960), RxMode.AM, 0.0)), 20.0)
    }

    @Test fun `de la CW manipulee dans la FI, lue par le decodeur CW`() {
        for (fs in listOf(16_000, 48_000)) {
            // Keyed 1.1 kHz above the dial; listened at +400 Hz in USB: a 700 Hz note.
            val manip = listOf(false to 600.0) + DecodeurCw.manipulation("VVV CQ DE F4IOZ K", 20) + listOf(false to 1500.0)
            val rnd = java.util.Random(5)
            val l = ArrayList<Short>()
            var t = 0L
            val rampe = fs * 0.004
            for ((on, ms) in manip) {
                val n = (ms * fs / 1000).toInt()
                for (i in 0 until n) {
                    val env = if (!on) 0.0 else minOf(1.0, i / rampe, (n - i) / rampe)
                    val v = 0.3 * env * cos(2 * PI * (FiPoste.CENTRE_HZ + 1_100.0) * t / fs) + 0.01 * rnd.nextGaussian()
                    l += (v * 32767).toInt().toShort(); t++
                }
            }
            val son = demodule(parSource(fs, l.toShortArray(), if (fs == 16_000) 320 else 960), RxMode.USB, 400.0, 500.0)
            val sb = StringBuilder()
            val d = DecodeurCw(Dsp.AUDIO_RATE, { sb.append(it) })
            var i = 0
            while (i < son.size) { val n = minOf(2048, son.size - i); d.ecoute(son.copyOfRange(i, i + n), n); i += n }
            assertTrue("$fs : $sb", sb.toString().contains("CQ DE F4IOZ K"))
        }
    }

    @Test fun `le debit de sortie est celui de la cle`() {
        // One second of IF in, one second of I/Q out at the dongle rate (within the filters' fill).
        for (fs in listOf(16_000, 48_000)) {
            val iq = parSource(fs, ton(fs, 0.0, 1.0), fs / 50)
            assertEquals(Dsp.RTL_RATE.toDouble(), iq.size / 2.0, Dsp.RTL_RATE * 0.002)
        }
    }

    @Test fun `AF au lieu de FI - l'avertissement`() {
        var now = 0L
        val s = SourceFiPoste(16_000, "reseau", horloge = { now })
        // A voice-like 800 Hz tone: what the rig sends set to AF.
        val af = ShortArray(320)
        for (b in 0 until 200) { for (k in af.indices) af[k] = (8000 * sin(2 * PI * 800.0 * (b * 320 + k) / 16_000)).toInt().toShort(); s.pousse(af, 320) }
        assertTrue(s.avertissement().orEmpty().contains("sur IF"))
        val f = SourceFiPoste(16_000, "reseau", horloge = { now })
        val fi = ton(16_000, 0.0, 4.0)
        for (b in 0 until 200) f.pousse(fi.copyOfRange(b * 320, b * 320 + 320), 320)
        assertNull(f.avertissement())
        now = 5_000L
        assertTrue(f.avertissement().orEmpty().startsWith("aucun son"))
    }

    @Test fun `la FI simulee - l'appel CW se lit par le reseau et par la carte`() {
        for (fs in listOf(16_000, 48_000)) {
            val x = ShortArray(fs * 14)
            FiSimulee(fs).bloc(x, x.size)
            val son = demodule(parSource(fs, x, fs / 50), RxMode.USB, 400.0, 500.0)
            val sb = StringBuilder()
            val d = DecodeurCw(Dsp.AUDIO_RATE, { sb.append(it) })
            var i = 0
            while (i < son.size) { val n = minOf(2048, son.size - i); d.ecoute(son.copyOfRange(i, i + n), n); i += n }
            assertTrue("$fs : $sb", sb.toString().contains("DE F4IOZ"))
        }
    }
}
