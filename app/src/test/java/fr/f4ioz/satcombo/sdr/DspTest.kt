/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The demodulation chain, checked on synthetic IQ.
 *
 * Same approach that caught real SSTV bugs: build a signal with a known
 * answer, run it through the real chain, check the number that comes out.
 * Decimation can be wrong without an RTL-SDR plugged in.
 */
class DspTest {

    // ------------------------------------------------------------- filter

    @Test
    fun le_filtre_passe_bas_a_un_gain_unite_en_continu() {
        val h = Dsp.lowPass(63, 8_000.0, 176_400.0)
        assertEquals(63, h.size)
        var sum = 0.0
        h.forEach { sum += it }
        assertTrue("gain continu $sum", abs(sum - 1.0) < 1e-4)
    }

    @Test
    fun le_filtre_a_toujours_un_nombre_impair_de_coefficients() {
        assertEquals(63, Dsp.lowPass(62, 1000.0, 48000.0).size)
        assertEquals(63, Dsp.lowPass(63, 1000.0, 48000.0).size)
    }

    @Test
    fun le_filtre_est_symetrique() {
        val h = Dsp.lowPass(31, 4000.0, 48000.0)
        for (i in h.indices) {
            assertTrue(abs(h[i] - h[h.size - 1 - i]) < 1e-6)
        }
    }

    @Test
    fun le_filtre_attenue_au_dela_de_la_coupure() {
        val fs = 176_400.0
        val h = Dsp.lowPass(63, 8_000.0, fs)
        assertTrue("hors bande ${gainAt(h, 40_000.0, fs)}", gainAt(h, 40_000.0, fs) < 0.02)
        assertTrue("en bande ${gainAt(h, 2_000.0, fs)}", gainAt(h, 2_000.0, fs) > 0.9)
    }

    private fun gainAt(h: FloatArray, hz: Double, fs: Double): Double {
        var re = 0.0
        var im = 0.0
        for (i in h.indices) {
            val ph = -2.0 * PI * hz * i / fs
            re += h[i] * cos(ph)
            im += h[i] * sin(ph)
        }
        return Math.hypot(re, im)
    }

    // ---------------------------------------------------------- decimation

    @Test
    fun le_decimateur_produit_le_bon_nombre_dechantillons() {
        val d = ComplexDecimator(Dsp.lowPass(31, 4000.0, 48000.0), 4)
        val i = FloatArray(400) { 1f }
        val q = FloatArray(400) { 0f }
        val oi = FloatArray(d.maxOut(400))
        val oq = FloatArray(d.maxOut(400))
        val n = d.process(i, q, 400, oi, oq)
        assertEquals(100, n)
    }

    @Test
    fun le_decimateur_garde_la_continuite_entre_deux_blocs() {
        // A slow sine split into blocks must give exactly the same result as
        // one piece. This catches filter-history index errors: otherwise a
        // discontinuity every 30 ms would pass for hiss.
        val taps = Dsp.lowPass(31, 2000.0, 48000.0)
        val total = 1200
        val i = FloatArray(total) { cos(2.0 * PI * 300.0 * it / 48000.0).toFloat() }
        val q = FloatArray(total) { sin(2.0 * PI * 300.0 * it / 48000.0).toFloat() }

        val whole = ComplexDecimator(taps, 4)
        val wi = FloatArray(whole.maxOut(total))
        val wq = FloatArray(whole.maxOut(total))
        val nw = whole.process(i, q, total, wi, wq)

        val split = ComplexDecimator(taps, 4)
        val si = FloatArray(whole.maxOut(total))
        val sq = FloatArray(whole.maxOut(total))
        var pos = 0
        var out = 0
        val chunks = intArrayOf(100, 250, 7, 343, 500)
        for (c in chunks) {
            val bi = FloatArray(c) { i[pos + it] }
            val bq = FloatArray(c) { q[pos + it] }
            val ti = FloatArray(split.maxOut(c))
            val tq = FloatArray(split.maxOut(c))
            val n = split.process(bi, bq, c, ti, tq)
            for (k in 0 until n) { si[out] = ti[k]; sq[out] = tq[k]; out++ }
            pos += c
        }
        assertEquals(total, pos)
        assertEquals(nw, out)
        for (k in 0 until nw) {
            assertTrue("écart à l'échantillon $k : ${wi[k]} vs ${si[k]}",
                abs(wi[k] - si[k]) < 1e-4)
            assertTrue(abs(wq[k] - sq[k]) < 1e-4)
        }
    }

    // ------------------------------------------------------- discriminator

    @Test
    fun le_discriminateur_mesure_la_deviation() {
        // To an FM discriminator, a carrier at a constant offset is a constant
        // deviation: the output must be a fixed value.
        val fs = 44_100.0
        val dev = 5_000.0
        val disc = FmDiscriminator(fs, dev)
        val n = 4000
        val i = FloatArray(n)
        val q = FloatArray(n)
        val offset = 2_500.0                    // half the max deviation
        for (k in 0 until n) {
            val ph = 2.0 * PI * offset * k / fs
            i[k] = cos(ph).toFloat()
            q[k] = sin(ph).toFloat()
        }
        val out = ShortArray(n)
        val produced = disc.process(i, q, n, out)
        assertEquals(n, produced)
        // Expected 0.5 × 26000 = 13000, skipping the first sample, which has
        // no predecessor.
        for (k in 10 until n) {
            assertTrue("échantillon $k = ${out[k]}", abs(out[k] - 13_000) < 200)
        }
    }

    @Test
    fun le_discriminateur_change_de_signe_avec_loffset() {
        val fs = 44_100.0
        val disc = FmDiscriminator(fs, 5_000.0)
        val n = 2000
        val i = FloatArray(n)
        val q = FloatArray(n)
        for (k in 0 until n) {
            val ph = -2.0 * PI * 2_500.0 * k / fs
            i[k] = cos(ph).toFloat()
            q[k] = sin(ph).toFloat()
        }
        val out = ShortArray(n)
        disc.process(i, q, n, out)
        for (k in 10 until n) assertTrue(out[k] < -12_000)
    }

    @Test
    fun le_niveau_suit_lamplitude() {
        val fs = 44_100.0
        val n = 1000
        fun mesure(amp: Float): Float {
            val disc = FmDiscriminator(fs, 5_000.0)
            val i = FloatArray(n) { amp * cos(2.0 * PI * 1000.0 * it / fs).toFloat() }
            val q = FloatArray(n) { amp * sin(2.0 * PI * 1000.0 * it / fs).toFloat() }
            disc.process(i, q, n, ShortArray(n))
            return disc.levelDb
        }
        val fort = mesure(1f)
        val faible = mesure(0.1f)
        assertTrue("fort=$fort faible=$faible", fort - faible > 15f)
        assertTrue(fort <= 1f)
    }

    // ------------------------------------------------------------- chain

    @Test
    fun la_chaine_retombe_sur_la_frequence_audio_du_sstv() {
        val chain = NfmChain()
        assertEquals(Dsp.AUDIO_RATE, chain.audioRate)
        assertEquals(Dsp.RTL_RATE, Dsp.AUDIO_RATE * Dsp.DECIM_1 * Dsp.DECIM_2)
    }

    @Test
    fun la_chaine_demodule_une_tonalite_fm() {
        // Synthetic carrier at centre, FM-modulated by a 1000 Hz tone with 3 kHz
        // deviation, like an FM beacon. The tone must come out at 1000 Hz.
        val chain = NfmChain()
        chain.deemphasis = false          // de-emphasis would skew the level
        val rate = Dsp.RTL_RATE
        val durMs = 300
        val n = rate * durMs / 1000
        val iq = ByteArray(n * 2)
        var phase = 0.0
        val toneHz = 1_000.0
        val devHz = 3_000.0
        for (k in 0 until n) {
            val inst = devHz * sin(2.0 * PI * toneHz * k / rate)
            phase += 2.0 * PI * inst / rate
            iq[2 * k] = ((cos(phase) * 120.0) + 127.5).toInt().coerceIn(0, 255).toByte()
            iq[2 * k + 1] = ((sin(phase) * 120.0) + 127.5).toInt().coerceIn(0, 255).toByte()
        }

        val out = ShortArray(chain.maxAudio(iq.size))
        val produced = chain.process(iq, iq.size, out)
        assertTrue("aucun audio produit", produced > Dsp.AUDIO_RATE / 10)

        // Skip filter transients.
        val skip = 400
        val used = produced - skip
        assertTrue(used > 4000)

        val f1000 = goertzel(out, skip, used, 1_000.0, Dsp.AUDIO_RATE.toDouble())
        val f2500 = goertzel(out, skip, used, 2_500.0, Dsp.AUDIO_RATE.toDouble())
        val f300 = goertzel(out, skip, used, 300.0, Dsp.AUDIO_RATE.toDouble())
        assertTrue("1 kHz=$f1000 vs 2,5 kHz=$f2500", f1000 > 10 * f2500)
        assertTrue("1 kHz=$f1000 vs 300 Hz=$f300", f1000 > 10 * f300)

        // Expected amplitude: 3000/5000 of the 26000 full scale.
        val crete = (0 until used).maxOf { abs(out[skip + it].toInt()) }
        assertTrue("crête $crete", crete in 12_000..20_000)
    }

    @Test
    fun la_chaine_supporte_un_decoupage_en_blocs() {
        // Same signal, split as USB reads do. Content must match a single block.
        val rate = Dsp.RTL_RATE
        val n = rate / 10
        val iq = ByteArray(n * 2)
        var phase = 0.0
        for (k in 0 until n) {
            val inst = 3_000.0 * sin(2.0 * PI * 1_000.0 * k / rate)
            phase += 2.0 * PI * inst / rate
            iq[2 * k] = ((cos(phase) * 120.0) + 127.5).toInt().coerceIn(0, 255).toByte()
            iq[2 * k + 1] = ((sin(phase) * 120.0) + 127.5).toInt().coerceIn(0, 255).toByte()
        }

        val whole = NfmChain().apply { deemphasis = false }
        val wo = ShortArray(whole.maxAudio(iq.size))
        val nw = whole.process(iq, iq.size, wo)

        val split = NfmChain().apply { deemphasis = false }
        val so = ShortArray(whole.maxAudio(iq.size))
        var pos = 0
        var out = 0
        val block = 24 * 1024 * 2      // multiple of 2 bytes and of the decimation
        while (pos < iq.size) {
            val len = minOf(block, iq.size - pos)
            val chunk = ByteArray(len)
            System.arraycopy(iq, pos, chunk, 0, len)
            val tmp = ShortArray(split.maxAudio(len))
            val got = split.process(chunk, len, tmp)
            for (k in 0 until got) { if (out < so.size) so[out++] = tmp[k] }
            pos += len
        }
        assertTrue("blocs $out vs bloc unique $nw", abs(out - nw) <= 4)
        var ecarts = 0
        for (k in 100 until minOf(out, nw)) {
            if (abs(wo[k] - so[k]) > 300) ecarts++
        }
        assertTrue("$ecarts échantillons divergents", ecarts == 0)
    }

    @Test
    fun la_desaccentuation_attenue_les_aigus() {
        val fs = 44_100.0
        fun reponse(hz: Double): Double {
            val de = Deemphasis(750.0, fs)
            val n = 8000
            val buf = ShortArray(n) { (10_000.0 * sin(2.0 * PI * hz * it / fs)).toInt().toShort() }
            de.process(buf, n)
            var peak = 0
            for (k in n / 2 until n) peak = maxOf(peak, abs(buf[k].toInt()))
            return peak.toDouble()
        }
        val grave = reponse(300.0)
        val aigu = reponse(3_000.0)
        assertTrue("grave=$grave aigu=$aigu", grave > aigu * 1.5)
    }

    @Test
    fun le_bloqueur_de_continu_supprime_le_decalage() {
        val dc = DcBlock()
        val n = 40_000
        val buf = ShortArray(n) { 5_000 }
        dc.process(buf, n)
        assertTrue("résidu ${buf[n - 1]}", abs(buf[n - 1].toInt()) < 500)
    }

    @Test
    fun la_sonde_de_spectre_trouve_une_porteuse_decalee() {
        val bins = 64
        val probe = SpectrumProbe(bins)
        val rate = Dsp.RTL_RATE
        val n = 8192
        val iq = ByteArray(n * 2)
        // Carrier a quarter of the sample rate above centre.
        val offset = rate / 4.0
        for (k in 0 until n) {
            val ph = 2.0 * PI * offset * k / rate
            iq[2 * k] = ((cos(ph) * 120.0) + 127.5).toInt().coerceIn(0, 255).toByte()
            iq[2 * k + 1] = ((sin(ph) * 120.0) + 127.5).toInt().coerceIn(0, 255).toByte()
        }
        probe.analyse(iq, iq.size, stride = 1)
        var best = 0
        for (b in 1 until bins) if (probe.bands[b] > probe.bands[best]) best = b
        // +rate/4 is three quarters across: bin 48 of 64.
        assertTrue("maximum au bin $best", abs(best - 48) <= 2)
    }

    // -------------------------------------------------------------- BLU

    @Test
    fun le_passe_bande_complexe_ne_garde_quun_cote() {
        val fs = 44_100.0
        val n = 16_384
        val bp = ComplexBandpass(255, 1_500.0, 2_400.0, fs)
        fun sortie(hz: Double): Double {
            bp.reset()
            val i = FloatArray(n)
            val q = FloatArray(n)
            for (k in 0 until n) {
                val ph = 2.0 * PI * hz * k / fs
                i[k] = cos(ph).toFloat()
                q[k] = sin(ph).toFloat()
            }
            val out = FloatArray(n)
            bp.process(i, q, n, out)
            var peak = 0.0
            for (k in n / 2 until n) peak = maxOf(peak, abs(out[k]).toDouble())
            return peak
        }
        val bon = sortie(1_500.0)
        val mauvais = sortie(-1_500.0)
        assertTrue("bon cote $bon", bon > 0.8)
        assertTrue("mauvais cote $mauvais (bon $bon)", mauvais < bon / 100.0)
    }

    @Test
    fun la_chaine_demodule_la_bande_laterale_superieure() {
        val audio = deuxTons(RxMode.USB)
        val g1500 = goertzel(audio, audio.size / 3, audio.size / 3, 1_500.0, 44_100.0)
        val g2500 = goertzel(audio, audio.size / 3, audio.size / 3, 2_500.0, 44_100.0)
        assertTrue("g1500=$g1500 g2500=$g2500", g1500 > g2500 * 20)
    }

    @Test
    fun la_chaine_demodule_la_bande_laterale_inferieure() {
        val audio = deuxTons(RxMode.LSB)
        val g1500 = goertzel(audio, audio.size / 3, audio.size / 3, 1_500.0, 44_100.0)
        val g2500 = goertzel(audio, audio.size / 3, audio.size / 3, 2_500.0, 44_100.0)
        assertTrue("g1500=$g1500 g2500=$g2500", g2500 > g1500 * 20)
    }

    /**
     * A signal with two lines: +1500 Hz (upper sideband) and −2500 Hz (lower
     * sideband). A correct SSB receiver hears only one at a time.
     */
    private fun deuxTons(mode: RxMode): ShortArray {
        val rate = Dsp.RTL_RATE.toDouble()
        val n = 262_144
        val iq = ByteArray(n * 2)
        for (k in 0 until n) {
            val p1 = 2.0 * PI * 1_500.0 * k / rate
            val p2 = 2.0 * PI * -2_500.0 * k / rate
            val i = cos(p1) + cos(p2)
            val q = sin(p1) + sin(p2)
            iq[2 * k] = ((i * 50.0) + 127.5).toInt().coerceIn(0, 255).toByte()
            iq[2 * k + 1] = ((q * 50.0) + 127.5).toInt().coerceIn(0, 255).toByte()
        }
        val chain = RxChain().apply { this.mode = mode }
        val out = ShortArray(chain.maxAudio(iq.size))
        val produced = chain.process(iq, iq.size, out)
        assertTrue("audio produit $produced", produced > 8_000)
        return out.copyOf(produced)
    }

    @Test
    fun la_chaine_demodule_lamplitude() {
        val rate = Dsp.RTL_RATE.toDouble()
        val n = 524_288
        val iq = ByteArray(n * 2)
        for (k in 0 until n) {
            val m = 1.0 + 0.5 * cos(2.0 * PI * 1_000.0 * k / rate)
            iq[2 * k] = ((m * 80.0) + 127.5).toInt().coerceIn(0, 255).toByte()
            iq[2 * k + 1] = 127.5.toInt().coerceIn(0, 255).toByte()
        }
        val chain = RxChain().apply { mode = RxMode.AM }
        val out = ShortArray(chain.maxAudio(iq.size))
        val produced = chain.process(iq, iq.size, out)
        val from = produced * 2 / 3
        val count = produced - from
        val g1000 = goertzel(out, from, count, 1_000.0, 44_100.0)
        val g3000 = goertzel(out, from, count, 3_000.0, 44_100.0)
        assertTrue("g1000=$g1000 g3000=$g3000", g1000 > 500 && g1000 > g3000 * 10)
    }

    @Test
    fun la_largeur_de_bande_depend_du_mode() {
        val chain = RxChain()
        chain.mode = RxMode.NFM
        assertEquals(16_000.0, chain.effectiveBandwidthHz, 1.0)
        chain.mode = RxMode.USB
        assertEquals(2_400.0, chain.effectiveBandwidthHz, 1.0)
        chain.mode = RxMode.AM
        assertEquals(6_000.0, chain.effectiveBandwidthHz, 1.0)
        chain.bandwidthHz = 1_800.0
        assertEquals(1_800.0, chain.effectiveBandwidthHz, 1.0)
        chain.bandwidthHz = 99_000.0
        assertEquals(24_000.0, chain.effectiveBandwidthHz, 1.0)
    }

    // -------------------------------------------------------- squelch

    @Test
    fun le_silencieux_a_une_hysteresis() {
        val sq = Squelch(thresholdDb = -40f, hysteresisDb = 4f)
        assertTrue(sq.update(-30f))          // strong signal: open
        assertTrue(sq.update(-43f))          // within hysteresis: stays open
        assertTrue(!sq.update(-50f))         // below threshold: closes
        assertTrue(!sq.update(-42f))         // not enough to reopen
        assertTrue(sq.update(-35f))          // above threshold: reopens
    }

    @Test
    fun le_silencieux_coupe_le_son_de_la_chaine() {
        val rate = Dsp.RTL_RATE.toDouble()
        val n = 131_072
        val iq = ByteArray(n * 2)
        for (k in 0 until n) {
            val ph = 2.0 * PI * 1_500.0 * k / rate
            iq[2 * k] = ((cos(ph) * 60.0) + 127.5).toInt().coerceIn(0, 255).toByte()
            iq[2 * k + 1] = ((sin(ph) * 60.0) + 127.5).toInt().coerceIn(0, 255).toByte()
        }
        val chain = RxChain().apply { mode = RxMode.USB }
        chain.squelch.thresholdDb = 40f      // unreachable threshold: all muted
        val out = ShortArray(chain.maxAudio(iq.size))
        val produced = chain.process(iq, iq.size, out)
        var peak = 0
        for (k in 0 until produced) peak = maxOf(peak, abs(out[k].toInt()))
        assertEquals("le silencieux laisse passer $peak", 0, peak)
    }

    @Test
    fun la_commande_de_gain_remonte_un_signal_faible() {
        val agc = AudioAgc()
        val n = 44_100
        val buf = FloatArray(n) { (0.001 * cos(2.0 * PI * 1_000.0 * it / 44_100.0)).toFloat() }
        val out = ShortArray(n)
        agc.process(buf, n, out)
        var peak = 0
        for (k in n / 2 until n) peak = maxOf(peak, abs(out[k].toInt()))
        assertTrue("niveau apres CAG $peak", peak > 5_000 && peak < 20_000)
    }

    // ---------------------------------------------------- spectrum and FFT

    @Test
    fun la_fft_trouve_une_raie_pure() {
        val n = 1024
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (k in 0 until n) {
            val ph = 2.0 * PI * 100.0 * k / n
            re[k] = cos(ph)
            im[k] = sin(ph)
        }
        Fft.transform(re, im)
        var best = 0
        var bestMag = 0.0
        for (k in 0 until n) {
            val m = re[k] * re[k] + im[k] * im[k]
            if (m > bestMag) { bestMag = m; best = k }
        }
        assertEquals(100, best)
    }

    @Test
    fun lanalyseur_de_spectre_place_la_raie_au_bon_endroit() {
        val an = SpectrumAnalyzer(1024)
        val n = 4096
        val i = FloatArray(n)
        val q = FloatArray(n)
        // One eighth of the sample rate above the tuned frequency.
        for (k in 0 until n) {
            val ph = 2.0 * PI * k / 8.0
            i[k] = cos(ph).toFloat()
            q[k] = sin(ph).toFloat()
        }
        assertTrue(an.push(i, q, n))
        var best = 0
        for (k in 1 until an.size) if (an.magDb[k] > an.magDb[best]) best = k
        // Centre (512) plus one eighth of 1024.
        assertTrue("maximum a la raie $best", abs(best - 640) <= 1)
        assertTrue("niveau ${an.magDb[best]}", an.magDb[best] > -6f)
    }

    @Test
    fun lanalyseur_de_spectre_recolle_les_blocs() {
        val an = SpectrumAnalyzer(1024)
        val i = FloatArray(300)
        val q = FloatArray(300)
        var trames = 0
        repeat(10) { bloc ->
            for (k in 0 until 300) {
                val ph = 2.0 * PI * (bloc * 300 + k) / 4.0
                i[k] = cos(ph).toFloat()
                q[k] = sin(ph).toFloat()
            }
            if (an.push(i, q, 300)) trames++
        }
        assertEquals(2, trames)
        var best = 0
        for (k in 1 until an.size) if (an.magDb[k] > an.magDb[best]) best = k
        assertTrue("maximum a la raie $best", abs(best - 768) <= 1)
    }

    // ------------------------------------------------- fine tuning and spectrum

    /** Builds raw IQ (unsigned bytes) with a single carrier [hz] from the tuned frequency. */
    private fun porteuse(hz: Double, n: Int, amp: Double = 90.0): ByteArray {
        val rate = Dsp.RTL_RATE.toDouble()
        val iq = ByteArray(n * 2)
        for (k in 0 until n) {
            val ph = 2.0 * PI * hz * k / rate
            iq[2 * k] = ((cos(ph) * amp) + 127.5).toInt().coerceIn(0, 255).toByte()
            iq[2 * k + 1] = ((sin(ph) * amp) + 127.5).toInt().coerceIn(0, 255).toByte()
        }
        return iq
    }

    @Test
    fun le_spectre_ne_bouge_pas_avec_laccord_fin() {
        // The bug that made tuning impossible in the field: the spectrum was
        // taken AFTER the offset mixer, so tapping a line made it run away by
        // twice the offset. The line must stay put whatever the offset.
        val n = 262_144
        val iq = porteuse(4_000.0, n)
        val hzParRaie = Dsp.RTL_RATE.toDouble() / Dsp.DECIM_1 / Dsp.SPECTRUM_SIZE

        fun raie(offset: Double): Int {
            val chain = RxChain().apply { offsetHz = offset }
            val out = ShortArray(chain.maxAudio(iq.size))
            chain.process(iq, iq.size, out, feedSpectrum = true)
            assertTrue("aucune trame de spectre", chain.spectrum.frames > 0)
            var best = 0
            for (k in 1 until chain.spectrum.size) {
                if (chain.spectrum.magDb[k] > chain.spectrum.magDb[best]) best = k
            }
            return best
        }

        val attendu = Dsp.SPECTRUM_SIZE / 2 + Math.round(4_000.0 / hzParRaie).toInt()
        val sansDecalage = raie(0.0)
        val avecDecalage = raie(4_000.0)
        assertTrue("raie sans décalage $sansDecalage, attendue $attendu",
            abs(sansDecalage - attendu) <= 2)
        assertTrue("raie avec décalage $avecDecalage, attendue $attendu",
            abs(avecDecalage - attendu) <= 2)
    }

    @Test
    fun laccord_fin_descend_la_station_visee_sur_zero() {
        // The sign mattered as much as the mixer position: "+4 kHz" must listen
        // 4 kHz ABOVE the displayed frequency, not below. Lines at +5500 and
        // −5500 Hz; in USB with the offset at +4000 Hz, only the first must be
        // heard, at 1500 Hz.
        val rate = Dsp.RTL_RATE.toDouble()
        val n = 262_144
        val iq = ByteArray(n * 2)
        for (k in 0 until n) {
            val p1 = 2.0 * PI * 5_500.0 * k / rate      // +4000 + 1500 Hz audio
            val p2 = 2.0 * PI * -5_500.0 * k / rate     // the image, on the wrong side
            val i = cos(p1) + cos(p2)
            val q = sin(p1) + sin(p2)
            iq[2 * k] = ((i * 45.0) + 127.5).toInt().coerceIn(0, 255).toByte()
            iq[2 * k + 1] = ((q * 45.0) + 127.5).toInt().coerceIn(0, 255).toByte()
        }
        val chain = RxChain().apply { mode = RxMode.USB; offsetHz = 4_000.0 }
        val out = ShortArray(chain.maxAudio(iq.size))
        val produced = chain.process(iq, iq.size, out)
        assertTrue("audio produit $produced", produced > 8_000)
        val from = produced / 2
        val count = produced - from
        val g1500 = goertzel(out, from, count, 1_500.0, 44_100.0)
        val g500 = goertzel(out, from, count, 500.0, 44_100.0)
        assertTrue("g1500=$g1500 g500=$g500", g1500 > g500 * 10)
    }

    @Test
    fun la_chaine_retrouve_la_raie_la_plus_forte() {
        // The "Peak" button relies on this: the reported offset must be the
        // carrier's, in hertz, not a noise line.
        val n = 262_144
        val iq = porteuse(-12_000.0, n)
        val chain = RxChain()
        val out = ShortArray(chain.maxAudio(iq.size))
        chain.process(iq, iq.size, out, feedSpectrum = true)
        val trouve = chain.peakOffsetHz(-40_000.0, 40_000.0)
        assertTrue("crête trouvée à $trouve Hz", abs(trouve + 12_000.0) < 200.0)
    }

    @Test
    fun la_recherche_de_crete_reste_dans_la_fenetre_demandee() {
        val n = 262_144
        val iq = porteuse(30_000.0, n)
        val chain = RxChain()
        val out = ShortArray(chain.maxAudio(iq.size))
        chain.process(iq, iq.size, out, feedSpectrum = true)
        // Window deliberately beside the carrier: the result must stay inside,
        // or the button would chase a station off screen.
        val trouve = chain.peakOffsetHz(-6_000.0, 6_000.0)
        assertTrue("crête hors fenêtre : $trouve", trouve >= -6_100.0 && trouve <= 6_100.0)
    }

    /**
     * FSK: two humps and nothing in the middle — what a radiosonde looks like,
     * and what fools peak search.
     */
    private fun fsk(centreHz: Double, deviationHz: Double, n: Int,
                    amp: Double = 90.0): ByteArray {
        val rate = Dsp.RTL_RATE.toDouble()
        val iq = ByteArray(n * 2)
        var ph = 0.0
        // 4800 baud, the RS41 rate.
        val perBit = (rate / 4_800.0).toInt().coerceAtLeast(1)
        var bit = 1
        for (k in 0 until n) {
            // As many ones as zeros, so both humps carry equal power and their
            // midpoint is the centre frequency. An unbalanced sequence shifts
            // the centroid, correctly — but that is not what is measured here.
            if (k % perBit == 0) bit = (k / perBit) % 2
            val f = centreHz + if (bit == 1) deviationHz else -deviationHz
            ph += 2.0 * PI * f / rate
            if (ph > PI) ph -= 2.0 * PI
            if (ph < -PI) ph += 2.0 * PI
            iq[2 * k] = ((cos(ph) * amp) + 127.5).toInt().coerceIn(0, 255).toByte()
            iq[2 * k + 1] = ((sin(ph) * amp) + 127.5).toInt().coerceIn(0, 255).toByte()
        }
        return iq
    }

    @Test
    fun le_recentrage_vise_le_milieu_des_deux_bosses() {
        // Auto-tune. On FSK the strongest line is a full deviation off: aiming
        // at the peak tunes 2400 Hz off, and an RS41 drops out for much less.
        // The centroid lands between the two humps.
        val n = 262_144
        val iq = fsk(6_000.0, 2_400.0, n)
        val chain = RxChain()
        val out = ShortArray(chain.maxAudio(iq.size))
        chain.process(iq, iq.size, out, feedSpectrum = true)
        val centre = chain.centroidOffsetHz()
        assertTrue("centre trouvé à $centre Hz", abs(centre - 6_000.0) < 900.0)
    }

    @Test
    fun le_recentrage_est_absolu_et_non_cumulatif() {
        // The spectrum is taken before the fine-tuning mixer, so the result does
        // not depend on the current offset. That makes continuous recentring
        // safe — otherwise it would add to itself every cycle and the station
        // would leave the band in three seconds.
        val n = 262_144
        val iq = fsk(6_000.0, 2_400.0, n)
        val chain = RxChain().apply { offsetHz = 6_000.0 }
        val out = ShortArray(chain.maxAudio(iq.size))
        chain.process(iq, iq.size, out, feedSpectrum = true)
        val centre = chain.centroidOffsetHz()
        assertTrue("le recentrage s'est cumulé : $centre Hz",
            abs(centre - 6_000.0) < 900.0)
    }

    @Test
    fun le_recentrage_ne_rend_rien_sans_signal() {
        // No spectrum frame, no tuning: zero means "don't know", and the caller
        // must keep the current tuning rather than jump to band centre.
        assertEquals(0.0, RxChain().centroidOffsetHz(), 1e-9)
    }

    @Test
    fun la_desaccentuation_rattrape_le_niveau_a_mille_hertz() {
        // A fixed factor of three once left 1000 Hz 11 dB too low: the 1750 Hz
        // tone burst became inaudible on a perfectly received signal. The
        // make-up gain is now computed.
        val de = Deemphasis(750.0, 44_100.0)
        assertTrue("gain de rattrapage ${de.makeupGain}",
            de.makeupGain > 3.5f && de.makeupGain < 7f)

        val fs = 44_100.0
        val n = 8_000
        val buf = ShortArray(n) { (8_000.0 * sin(2.0 * PI * 1_000.0 * it / fs)).toInt().toShort() }
        de.process(buf, n)
        var peak = 0
        for (k in n / 2 until n) peak = maxOf(peak, abs(buf[k].toInt()))
        assertTrue("1 kHz ressort à $peak au lieu de 8 000", abs(peak - 8_000) < 1_500)
    }

    @Test
    fun la_saturation_de_lentree_est_signalee() {
        // A transmitter three metres away overloads the dongle front end: noise
        // vanishes, the screen shows a strong signal, and nothing comes out.
        // The app cannot fix it but must say so.
        val n = 65_536
        val propre = porteuse(1_000.0, n, amp = 60.0)
        val sature = porteuse(1_000.0, n, amp = 400.0)

        val c1 = RxChain()
        c1.process(propre, propre.size, ShortArray(c1.maxAudio(propre.size)))
        assertTrue("faux positif : ${c1.clipRatio}", c1.clipRatio < 0.002f)

        val c2 = RxChain()
        c2.process(sature, sature.size, ShortArray(c2.maxAudio(sature.size)))
        assertTrue("saturation non vue : ${c2.clipRatio}", c2.clipRatio > 0.05f)
    }

    @Test
    fun le_vumetre_audio_suit_la_modulation() {
        // An unmodulated carrier: the AF bar must stay at the floor while the
        // RF bar is at max. That pair tells "no modulation" from "no signal".
        // Measure on the SECOND block: a discriminator's very first sample has
        // no predecessor and gives a one-off full-scale spike.
        val n = 131_072
        val chain = RxChain().apply { mode = RxMode.NFM }
        chain.squelch.thresholdDb = -120f
        val plate = porteuse(0.0, n)
        val out = ShortArray(chain.maxAudio(plate.size))
        chain.process(plate, plate.size, out)
        chain.process(plate, plate.size, out)
        val pur = chain.audioPeak

        val rate = Dsp.RTL_RATE.toDouble()
        val iq = ByteArray(2 * n * 2)
        var phase = 0.0
        for (k in 0 until 2 * n) {
            phase += 2.0 * PI * (3_000.0 * sin(2.0 * PI * 1_000.0 * k / rate)) / rate
            iq[2 * k] = ((cos(phase) * 90.0) + 127.5).toInt().coerceIn(0, 255).toByte()
            iq[2 * k + 1] = ((sin(phase) * 90.0) + 127.5).toInt().coerceIn(0, 255).toByte()
        }
        val c2 = RxChain().apply { mode = RxMode.NFM }
        c2.squelch.thresholdDb = -120f
        val moitie = ByteArray(n * 2)
        val o2 = ShortArray(c2.maxAudio(n * 2))
        System.arraycopy(iq, 0, moitie, 0, n * 2)
        c2.process(moitie, moitie.size, o2)
        System.arraycopy(iq, n * 2, moitie, 0, n * 2)
        c2.process(moitie, moitie.size, o2)
        val module = c2.audioPeak
        assertTrue("porteuse pure $pur, modulée $module", pur < 0.05f && module > 0.3f)
    }

    private fun goertzel(x: ShortArray, from: Int, count: Int, hz: Double, fs: Double): Double {
        val w = 2.0 * PI * hz / fs
        val coeff = 2.0 * cos(w)
        var s0: Double
        var s1 = 0.0
        var s2 = 0.0
        for (k in 0 until count) {
            s0 = x[from + k] + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return Math.sqrt(s1 * s1 + s2 * s2 - coeff * s1 * s2) / count
    }
}
