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
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** The sound cleaning, on synthetic signals with a known answer. */
class NettoyageTest {

    private val fs = 44_100.0

    // ------------------------------------------------------------- noise blanker

    /** USB tone at +1 kHz in light noise, with or without a click every 2 ms; returns the audio. */
    private fun chaineAvecImpulsions(impulsions: Boolean, nb: Boolean): ShortArray {
        val rate = Dsp.RTL_RATE.toDouble()
        val n = 1_058_400
        val rnd = Random(7)
        val iq = ShortArray(2 * n)
        for (k in 0 until n) {
            val ph = 2.0 * PI * 1_000.0 * k / rate
            var i = 0.01 * cos(ph) + 0.002 * rnd.nextGaussian()
            var q = 0.01 * sin(ph) + 0.002 * rnd.nextGaussian()
            if (impulsions && k % 2_117 < 3) { i += if (rnd.nextBoolean()) 0.9 else -0.9; q += if (rnd.nextBoolean()) 0.9 else -0.9 }
            iq[2 * k] = (i * 32767).toInt().coerceIn(-32767, 32767).toShort()
            iq[2 * k + 1] = (q * 32767).toInt().coerceIn(-32767, 32767).toShort()
        }
        val chain = RxChain().apply { mode = RxMode.USB; cleaning = RxCleaning(nb = nb) }
        val out = ShortArray(n / 24 + 10)
        val bloc = 16_384
        var p = 0; var o = 0
        val tmp = ShortArray(bloc / 24 + 10)
        while (p < n) {
            val m = minOf(bloc, n - p)
            val r = chain.process16(iq.copyOfRange(2 * p, 2 * (p + m)), 2 * m, tmp)
            System.arraycopy(tmp, 0, out, o, r); o += r; p += m
        }
        return out.copyOf(o)
    }

    /** Tone-to-rest ratio at [hz], dB, over the second half. */
    private fun snr(a: ShortArray, hz: Double): Double {
        val x = DoubleArray(a.size / 2) { a[a.size / 2 + it].toDouble() }
        val ton = tonPuissance(x, hz)
        val tot = x.sumOf { it * it } / x.size
        return 10 * log10(ton / (tot - ton))
    }

    @Test
    fun lantiparasite_rend_le_rapport_signal_bruit() {
        val propre = snr(chaineAvecImpulsions(false, false), 1_000.0)
        val sale = snr(chaineAvecImpulsions(true, false), 1_000.0)
        val nettoye = snr(chaineAvecImpulsions(true, true), 1_000.0)
        val sansImpulsionAvecNb = snr(chaineAvecImpulsions(false, true), 1_000.0)
        println("antiparasite : propre %.1f dB, impulsions %.1f dB, impulsions + NB %.1f dB, propre + NB %.1f dB".format(propre, sale, nettoye, sansImpulsionAvecNb))
        assertTrue("les impulsions doivent gêner", sale < propre - 10)
        // Blanking 1 % of the time leaves sidebands near −20 dB: most, not all, of the clean ratio.
        assertTrue("le NB rend le rapport", nettoye > sale + 15 && nettoye > 30)
        assertTrue("sans impulsion, le NB ne coûte rien", sansImpulsionAvecNb > propre - 1)
    }

    @Test
    fun lantiparasite_laisse_passer_une_station_forte_qui_arrive() {
        val nb = NoiseBlanker(1_000_000.0)
        val n = 200_000
        val i = FloatArray(n) { if (it < 100_000) 0.001f else 0.5f }
        val q = FloatArray(n)
        nb.process(i, q, n)
        // Blanked for under a millisecond, then through.
        for (k in 101_000 until n) assertEquals(0.5f, i[k], 1e-4f)
    }

    // ------------------------------------------------------------- auto notch

    private fun bruit(n: Int, sigma: Double, seed: Long = 3): DoubleArray { val r = Random(seed); return DoubleArray(n) { sigma * r.nextGaussian() } }

    @Test
    fun le_notch_automatique_eteint_une_porteuse_en_une_demi_seconde() {
        val n = (fs * 1.5).toInt()
        val b = bruit(n, 0.03)
        val x = FloatArray(n) { (0.3 * sin(2 * PI * 1_234.5 * it / fs) + b[it]).toFloat() }
        val ent = DoubleArray(n) { x[it].toDouble() }
        val an = AutoNotch(fs)
        an.process(x, n)
        val d = (fs * 0.5).toInt()
        val avant = tonPuissance(ent.copyOfRange(d, n), 1_234.5)
        val apres = tonPuissance(DoubleArray(n - d) { x[d + it].toDouble() }, 1_234.5)
        val att = 10 * log10(avant / apres)
        println("notch auto : porteuse atténuée de %.1f dB après 0,5 s ; notches %s".format(att, an.notched()))
        assertTrue("atténuation $att dB", att >= 30)
    }

    @Test
    fun le_notch_automatique_suit_trois_porteuses() {
        val n = (fs * 1.5).toInt()
        val b = bruit(n, 0.02)
        val f = doubleArrayOf(700.0, 1_510.0, 2_333.0)
        val x = FloatArray(n) { k -> (f.sumOf { 0.15 * sin(2 * PI * it * k / fs) } + b[k]).toFloat() }
        val ent = DoubleArray(n) { x[it].toDouble() }
        AutoNotch(fs).process(x, n)
        val d = (fs * 0.6).toInt()
        for (h in f) {
            val att = 10 * log10(tonPuissance(ent.copyOfRange(d, n), h) / tonPuissance(DoubleArray(n - d) { x[d + it].toDouble() }, h))
            assertTrue("$h Hz : $att dB", att >= 25)
        }
    }

    @Test
    fun le_notch_automatique_laisse_passer_la_voix() {
        val n = (fs * 4).toInt()
        val v = voix(n, fs)
        val x = FloatArray(n) { v[it].toFloat() }
        AutoNotch(fs).process(x, n)
        val e0 = v.sumOf { it * it }
        val e1 = x.sumOf { it.toDouble() * it }
        println("notch auto sur la voix : %.2f dB".format(10 * log10(e1 / e0)))
        assertTrue("voix gardée ${e1 / e0}", e1 / e0 > 0.85)
    }

    @Test
    fun en_cw_le_notch_automatique_garde_la_manipulation() {
        val n = (fs * 6).toInt()
        // « CQ » again and again at 18 wpm (dot 67 ms), 700 Hz, never more than three dots long.
        val motif = "1110101110100011101011101110100000001110101110100011101011101110100000000"
        val point = (fs * 0.067).toInt()
        val b = bruit(n, 0.01)
        val x = FloatArray(n) { k -> ((if (motif[(k / point) % motif.length] == '1') 0.3 * sin(2 * PI * 700.0 * k / fs) else 0.0) + b[k]).toFloat() }
        val e0 = x.sumOf { it.toDouble() * it }
        val an = AutoNotch(fs).apply { persistS = 2.0 }
        an.process(x, n)
        val e1 = x.sumOf { it.toDouble() * it }
        println("notch auto en CW : %.2f dB".format(10 * log10(e1 / e0)))
        assertTrue("CW gardée ${e1 / e0}", e1 / e0 > 0.85)
        // A steady carrier still goes, after its two seconds.
        val y = FloatArray(n) { (0.3 * sin(2 * PI * 900.0 * it / fs)).toFloat() }
        AutoNotch(fs).apply { persistS = 2.0 }.process(y, n)
        val d = (fs * 3).toInt()
        val att = 10 * log10(0.045 / tonPuissance(DoubleArray(n - d) { y[d + it].toDouble() }, 900.0))
        assertTrue("porteuse en CW : $att dB", att >= 30)
    }

    @Test
    fun le_notch_manuel_coupe_sa_frequence_seulement() {
        val f = NotchFilter().apply { set(1_000.0, 80.0, fs) }
        fun gain(hz: Double): Double {
            f.reset()
            val n = 44_100
            val x = FloatArray(n) { sin(2 * PI * hz * it / fs).toFloat() }
            f.process(x, n)
            return 10 * log10(tonPuissance(DoubleArray(n / 2) { x[n / 2 + it].toDouble() }, hz) / 0.5)
        }
        assertTrue(gain(1_000.0) < -40)
        assertTrue(gain(1_300.0) > -1)
        assertTrue(gain(700.0) > -1)
    }

    // ------------------------------------------------------------- noise reduction

    /**
     * Speech-like: voiced syllables (harmonics of a 110–170 Hz pitch that
     * glides and wobbles, shaped by two moving formants, 300–3000 Hz), 250 ms
     * on, 150 ms off.
     */
    private fun voix(n: Int, rate: Double): DoubleArray {
        val out = DoubleArray(n)
        val ph = DoubleArray(30)
        for (k in 0 until n) {
            val t = k / rate
            val syl = (t / 0.4).toInt()
            val dans = t - syl * 0.4
            val env = if (dans < 0.25) sin(PI * dans / 0.25) else 0.0
            val f0 = 110.0 + 30.0 * ((syl * 7) % 5) / 4.0 + 20.0 * dans + 3.0 * sin(2 * PI * 5.0 * t)
            val f1 = 500.0 + 300.0 * sin(2 * PI * 0.7 * t + syl)
            val f2 = 1_500.0 + 600.0 * sin(2 * PI * 0.45 * t + 2.0 * syl)
            var s = 0.0
            for (h in 1 until 30) {
                val fh = h * f0
                ph[h] += 2 * PI * fh / rate
                if (fh < 300.0 || fh > 3_000.0) continue
                val a = 1.0 / (1.0 + ((fh - f1) / 150.0).let { it * it }) + 0.6 / (1.0 + ((fh - f2) / 250.0).let { it * it })
                s += a * sin(ph[h])
            }
            out[k] = 0.1 * env * s
        }
        return out
    }

    /** Segmental SNR, dB, over 20 ms frames holding speech, each bounded to −10…35 dB. */
    private fun snrSeg(propre: DoubleArray, y: DoubleArray, rate: Double): Double {
        val l = (rate * 0.02).toInt()
        val nb = propre.size / l
        val e = DoubleArray(nb) { f -> (0 until l).sumOf { propre[f * l + it] * propre[f * l + it] } }
        val seuil = e.max() * 0.01
        var s = 0.0; var c = 0
        for (f in 0 until nb) {
            if (e[f] < seuil) continue
            var d = 0.0
            for (i in 0 until l) { val r = y[f * l + i] - propre[f * l + i]; d += r * r }
            s += (10 * log10(e[f] / (d + 1e-30))).coerceIn(-10.0, 35.0); c++
        }
        return s / c
    }

    private fun essaiReduction(force: Int, bandeLimitee: Boolean): Triple<Double, Double, Double> {
        val n = (fs * 8).toInt()
        val v = voix(n, fs)
        var b = bruit(n, 1.0, 11)
        if (bandeLimitee) {
            // The noise an SSB filter lets through: 300–2700 Hz.
            val bf = FloatArray(n) { b[it].toFloat() }
            val out = FloatArray(n)
            ComplexBandpass(255, 1_500.0, 2_400.0, fs).process(bf, FloatArray(n), n, out)
            b = DoubleArray(n) { out[it].toDouble() }
        }
        // Noise level: overall SNR 5 dB over the syllables.
        val pv = v.sumOf { it * it } / n
        val pb = b.sumOf { it * it } / n
        val k = sqrt(pv / pb / 10.0.pow(0.5))
        val x = FloatArray(n) { (v[it] + k * b[it]).toFloat() }
        val ent = DoubleArray(n) { x[it].toDouble() }
        val nr = NoiseReduction(fs).apply { strength = force }
        nr.process(x, n)
        val d = nr.delay
        val y = DoubleArray(n - d) { x[it + d].toDouble() }
        val ref = v.copyOf(n - d)
        // The first second lets the noise estimate settle.
        val s0 = (fs * 1).toInt()
        val avant = snrSeg(ref.copyOfRange(s0, ref.size), ent.copyOfRange(s0, n - d), fs)
        val apres = snrSeg(ref.copyOfRange(s0, ref.size), y.copyOfRange(s0, y.size), fs)
        val crete = y.maxOf { Math.abs(it) } / ent.maxOf { Math.abs(it) }
        return Triple(avant, apres, crete)
    }

    private fun Double.pow(e: Double) = Math.pow(this, e)

    @Test
    fun la_reduction_de_bruit_gagne_six_db_sur_une_voix_dans_le_souffle() {
        for (force in 1..3) for (bl in listOf(false, true)) {
            val (avant, apres, crete) = essaiReduction(force, bl)
            println("réduction de bruit force $force, bruit ${if (bl) "300–2700 Hz" else "blanc"} : SNR segmentaire %.1f → %.1f dB (%+.1f), crête ×%.2f"
                .format(avant, apres, apres - avant, crete))
            if (force >= 2 && !bl) assertTrue("force $force : ${apres - avant} dB", apres - avant >= 6)
            assertTrue("pas d'écrêtage : crête ×$crete", crete <= 1.0)
        }
    }

    @Test
    fun la_reduction_de_bruit_retarde_de_moins_de_40_ms() {
        val nr = NoiseReduction(fs)
        assertTrue(nr.delay / fs < 0.040)
        // And at the 22 050 Hz of the browsers' stream.
        val nr2 = NoiseReduction(22_050.0)
        assertTrue(nr2.delay / 22_050.0 < 0.040)
    }

    @Test
    fun le_cout_tient_dans_un_telephone() {
        val n = (fs * 10).toInt()
        val v = voix(n, fs); val b = bruit(n, 0.02)
        val x = FloatArray(n) { (v[it] + b[it]).toFloat() }
        fun mesure(nom: String, rate: Double, bloc: Int, total: Int, f: (Int, Int) -> Unit): Double {
            repeat(2) { var p = 0; while (p < total) { val m = minOf(bloc, total - p); f(p, m); p += m } }
            val t0 = System.nanoTime()
            var p = 0; while (p < total) { val m = minOf(bloc, total - p); f(p, m); p += m }
            val ns = (System.nanoTime() - t0).toDouble() / total
            println("%s : %.1f ns/échantillon, %.2f %% d'un cœur à %.0f Hz".format(nom, ns, ns * rate / 1e7, rate))
            return ns * rate / 1e7
        }
        val nr = NoiseReduction(fs).apply { strength = 3 }
        val y = x.copyOf()
        val pNr = mesure("réduction de bruit", fs, 2_048, n) { p, m -> val t = y.copyOfRange(p, p + m); nr.process(t, m) }
        val an = AutoNotch(fs)
        mesure("notch automatique", fs, 2_048, n) { p, m -> val t = y.copyOfRange(p, p + m); an.process(t, m) }
        val rate = Dsp.RTL_RATE.toDouble()
        val nI = FloatArray(16_384) { (0.01 * cos(it * 0.1)).toFloat() }; val nQ = FloatArray(16_384)
        val nbk = NoiseBlanker(rate)
        mesure("antiparasite", rate, 16_384, 16_384 * 60) { _, m -> nbk.process(nI, nQ, m) }
        assertTrue("réduction de bruit : $pNr %", pNr < 5)
    }

    // ------------------------------------------------------------- AGC, settings

    @Test
    fun la_cag_rapide_remonte_plus_vite_que_la_lente() {
        fun remontee(v: AgcSpeed): Double {
            val agc = AudioAgc(rate = fs).apply { speed = v }
            val n = (fs * 3).toInt()
            val x = FloatArray(n) { k -> ((if (k < fs) 0.5 else 0.01) * sin(2 * PI * 1_000.0 * k / fs)).toFloat() }
            val out = ShortArray(n)
            agc.process(x, n, out)
            // Level 1.5 s after the drop.
            val d = (fs * 2.5).toInt()
            return sqrt((d until d + 4_410).sumOf { out[it].toDouble() * out[it] } / 4_410)
        }
        val lente = remontee(AgcSpeed.LENTE); val moy = remontee(AgcSpeed.MOYENNE); val rapide = remontee(AgcSpeed.RAPIDE)
        assertTrue("lente $lente < moyenne $moy < rapide $rapide", lente < moy && moy < rapide)
        assertEquals(AgcSpeed.MOYENNE, AudioAgc().speed)
    }

    @Test
    fun les_reglages_se_gardent_par_mode() {
        val c = RxCleaning.OFF.avec(mapOf("nb" to "1", "nbSeuil" to "50", "notchAuto" to "1", "nr" to "2", "cag" to "rapide", "notchHz" to "1234"))
        assertTrue(c.nb && c.autoNotch && !c.notch)
        assertEquals(20f, c.nbThreshold)
        assertEquals(2, c.nr)
        assertEquals(AgcSpeed.RAPIDE, c.agc)
        val t = RxCleaning.texte(mapOf(RxMode.USB to c))
        assertEquals(c, RxCleaning.parMode(t)[RxMode.USB])
        assertEquals(null, RxCleaning.parMode(t)[RxMode.NFM])
        assertEquals(emptyMap<RxMode, RxCleaning>(), RxCleaning.parMode("pas du json"))
    }

    @Test
    fun la_chaine_sans_nettoyage_ne_change_pas() {
        val rate = Dsp.RTL_RATE.toDouble()
        val n = 200_000
        val iq = ByteArray(2 * n) { k -> ((if (k % 2 == 0) cos(2 * PI * 1_200.0 * (k / 2) / rate) else sin(2 * PI * 1_200.0 * (k / 2) / rate)) * 60 + 127.5).toInt().toByte() }
        val a = RxChain().apply { mode = RxMode.USB }
        val b = RxChain().apply { mode = RxMode.USB; cleaning = RxCleaning(notchHz = 500) }
        val oa = ShortArray(a.maxAudio(iq.size)); val ob = ShortArray(b.maxAudio(iq.size))
        val na = a.process(iq, iq.size, oa); val nbb = b.process(iq, iq.size, ob)
        assertEquals(na, nbb)
        for (k in 0 until na) assertEquals(oa[k], ob[k])
    }

    // ------------------------------------------------------------- a real recording

    /**
     * The owner's SDR sound, when its path is given in SATME_FLUX_SDR: SatMe-PC's
     * GET /api/son/flux?source=sdr (a 4-byte rate, then s16le mono) or a mono
     * 16-bit WAV. Prints the effect on the real hiss alone (how far the floor
     * drops), then with a synthetic voice mixed into that real hiss at 5 dB:
     * segmental SNR, and how much the frames holding the voice lose against
     * the frames holding none.
     */
    @Test
    fun sur_un_enregistrement_reel() {
        val chemin = System.getenv("SATME_FLUX_SDR")
        assumeTrue(chemin != null && File(chemin).isFile)
        val d = File(chemin!!).readBytes()
        fun le32(p: Int) = (d[p].toInt() and 0xff) or ((d[p + 1].toInt() and 0xff) shl 8) or ((d[p + 2].toInt() and 0xff) shl 16) or ((d[p + 3].toInt() and 0xff) shl 24)
        val wav = d.size > 44 && String(d, 0, 4) == "RIFF"
        val rate = (if (wav) le32(24) else le32(0)).toDouble()
        val debut = if (wav) 44 else 4
        val n = minOf((d.size - debut) / 2, (rate * 60).toInt())
        val reel = DoubleArray(n) { (((d[debut + 2 * it].toInt() and 0xff) or (d[debut + 1 + 2 * it].toInt() shl 8)).toShort()).toDouble() }
        val l = (rate * 0.02).toInt()
        fun trames(z: DoubleArray) = DoubleArray(z.size / l) { f -> 10 * log10((0 until l).sumOf { z[f * l + it] * z[f * l + it] } / l + 1e-9) }
        fun passe(x: DoubleArray, force: Int): Pair<DoubleArray, List<Double>> {
            val y = FloatArray(x.size) { x[it].toFloat() }
            val an = AutoNotch(rate); an.process(y, y.size)
            val nr = NoiseReduction(rate).apply { strength = force }; nr.process(y, y.size)
            return DoubleArray(x.size - nr.delay) { y[it + nr.delay].toDouble() } to an.notched()
        }
        val v = voix(n, rate)
        val pv = v.sumOf { it * it } / n
        val pb = reel.sumOf { it * it } / n
        val k = sqrt(pb / pv * 10.0.pow(0.5))
        val vs = DoubleArray(n) { v[it] * k }
        val mix = DoubleArray(n) { vs[it] + reel[it] }
        val s0 = (rate * 1).toInt()
        for (force in 1..3) {
            val (y, notches) = passe(reel, force)
            val ta = trames(reel.copyOf(y.size)); val tb = trames(y)
            val sec = s0 / l
            val fond = (sec until tb.size).map { tb[it] - ta[it] }.average()
            val (ym, _) = passe(mix, force)
            val ref = vs.copyOf(ym.size)
            val avant = snrSeg(ref.copyOfRange(s0, ref.size), mix.copyOfRange(s0, ym.size), rate)
            val apres = snrSeg(ref.copyOfRange(s0, ref.size), ym.copyOfRange(s0, ym.size), rate)
            val tv = trames(ref); val tx = trames(mix.copyOf(ym.size)); val ty = trames(ym)
            val seuil = tv.max() - 10
            val parole = (sec until ty.size).filter { tv[it] > seuil }.map { ty[it] - tx[it] }.average()
            val silence = (sec until ty.size).filter { tv[it] < -50 }.map { ty[it] - tx[it] }.average()
            println(("enregistrement %s (%.0f Hz, %.1f s), force %d : fond de bruit %+.1f dB ; avec une voix à 5 dB dans ce bruit : SNR segmentaire %.1f → %.1f dB, " +
                "trames de voix %+.1f dB, trames sans voix %+.1f dB ; porteuses notchées %s")
                .format(File(chemin).name, rate, n / rate, force, fond, avant, apres, parole, silence, notches.map { it.toInt() }))
        }
    }

    /** Power between 300 and 2700 Hz in [l] samples from [p] (Goertzel every 100 Hz). */
    private fun bande(z: DoubleArray, p: Int, l: Int, rate: Double): Double {
        var s = 0.0
        var f = 300.0
        while (f <= 2_700.0) { s += tonPuissance(z.copyOfRange(p, p + l), f, rate); f += 100.0 }
        return s
    }

    /** Power of the [hz] component (sine of amplitude A → A²/2). */
    private fun tonPuissance(x: DoubleArray, hz: Double, rate: Double = fs): Double {
        var re = 0.0; var im = 0.0
        for (k in x.indices) { val p = 2 * PI * hz * k / rate; re += x[k] * cos(p); im -= x[k] * sin(p) }
        val a = 2 * sqrt(re * re + im * im) / x.size
        return a * a / 2
    }
}
