/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
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
 * La chaîne de démodulation, vérifiée sur de l'IQ fabriqué.
 *
 * Le principe est celui qui a déjà attrapé deux vrais défauts dans le moteur
 * SSTV : on fabrique un signal dont on connaît la réponse exacte, on le passe
 * dans la vraie chaîne, et on vérifie le chiffre qui sort. Une clé RTL-SDR n'a
 * pas besoin d'être branchée pour que la décimation soit fausse.
 */
class DspTest {

    // ------------------------------------------------------------- filtre

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

    // ---------------------------------------------------------- décimation

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
        // Une sinusoïde lente découpée en blocs doit donner exactement le même
        // résultat qu'en un seul morceau. C'est le test qui attrape les erreurs
        // d'indice de l'historique du filtre : sans lui, une discontinuité
        // toutes les 30 ms passerait pour du souffle.
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

    // ------------------------------------------------------- discriminateur

    @Test
    fun le_discriminateur_mesure_la_deviation() {
        // Une porteuse décalée d'un offset constant est, pour un discriminateur
        // FM, une déviation constante : la sortie doit être une valeur fixe.
        val fs = 44_100.0
        val dev = 5_000.0
        val disc = FmDiscriminator(fs, dev)
        val n = 4000
        val i = FloatArray(n)
        val q = FloatArray(n)
        val offset = 2_500.0                    // moitié de la déviation max
        for (k in 0 until n) {
            val ph = 2.0 * PI * offset * k / fs
            i[k] = cos(ph).toFloat()
            q[k] = sin(ph).toFloat()
        }
        val out = ShortArray(n)
        val produced = disc.process(i, q, n, out)
        assertEquals(n, produced)
        // Attendu : 0,5 * 26 000 = 13 000, en ignorant le tout premier
        // échantillon qui n'a pas de précédent.
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

    // ------------------------------------------------------------- chaîne

    @Test
    fun la_chaine_retombe_sur_la_frequence_audio_du_sstv() {
        val chain = NfmChain()
        assertEquals(Dsp.AUDIO_RATE, chain.audioRate)
        assertEquals(Dsp.RTL_RATE, Dsp.AUDIO_RATE * Dsp.DECIM_1 * Dsp.DECIM_2)
    }

    @Test
    fun la_chaine_demodule_une_tonalite_fm() {
        // Signal de synthèse : porteuse au centre, modulée en fréquence par une
        // sinusoïde de 1 000 Hz avec 3 kHz de déviation — exactement ce que
        // produit une balise FM. On vérifie que la tonalité ressort à 1 000 Hz.
        val chain = NfmChain()
        chain.deemphasis = false          // la désaccentuation fausserait le niveau
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

        // On saute le régime transitoire des filtres.
        val skip = 400
        val used = produced - skip
        assertTrue(used > 4000)

        val f1000 = goertzel(out, skip, used, 1_000.0, Dsp.AUDIO_RATE.toDouble())
        val f2500 = goertzel(out, skip, used, 2_500.0, Dsp.AUDIO_RATE.toDouble())
        val f300 = goertzel(out, skip, used, 300.0, Dsp.AUDIO_RATE.toDouble())
        assertTrue("1 kHz=$f1000 vs 2,5 kHz=$f2500", f1000 > 10 * f2500)
        assertTrue("1 kHz=$f1000 vs 300 Hz=$f300", f1000 > 10 * f300)

        // Amplitude attendue : 3 000 / 5 000 de la pleine échelle de 26 000.
        val crete = (0 until used).maxOf { abs(out[skip + it].toInt()) }
        assertTrue("crête $crete", crete in 12_000..20_000)
    }

    @Test
    fun la_chaine_supporte_un_decoupage_en_blocs() {
        // Même signal, découpé comme le fait la lecture USB. Le contenu doit
        // rester identique à un bloc unique.
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
        val block = 24 * 1024 * 2      // multiple de 2 octets et de la décimation
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
        // Porteuse à un quart du débit au-dessus du centre.
        val offset = rate / 4.0
        for (k in 0 until n) {
            val ph = 2.0 * PI * offset * k / rate
            iq[2 * k] = ((cos(ph) * 120.0) + 127.5).toInt().coerceIn(0, 255).toByte()
            iq[2 * k + 1] = ((sin(ph) * 120.0) + 127.5).toInt().coerceIn(0, 255).toByte()
        }
        probe.analyse(iq, iq.size, stride = 1)
        var best = 0
        for (b in 1 until bins) if (probe.bands[b] > probe.bands[best]) best = b
        // +rate/4 correspond au trois-quarts de la largeur : bin 48 sur 64.
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
     * Un signal a deux raies : +1 500 Hz (dans la bande superieure) et
     * -2 500 Hz (dans la bande inferieure). Un recepteur BLU correct n'en
     * entend qu'une seule a la fois.
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

    // -------------------------------------------------------- silencieux

    @Test
    fun le_silencieux_a_une_hysteresis() {
        val sq = Squelch(thresholdDb = -40f, hysteresisDb = 4f)
        assertTrue(sq.update(-30f))          // signal franc : ouvert
        assertTrue(sq.update(-43f))          // dans l'hysteresis : reste ouvert
        assertTrue(!sq.update(-50f))         // sous le seuil : ferme
        assertTrue(!sq.update(-42f))         // pas assez pour rouvrir
        assertTrue(sq.update(-35f))          // au-dessus du seuil : rouvre
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
        chain.squelch.thresholdDb = 40f      // seuil inatteignable : tout est coupe
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

    // ---------------------------------------------------- spectre et FFT

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
        // Un huitieme du debit au-dessus de la frequence d'accord.
        for (k in 0 until n) {
            val ph = 2.0 * PI * k / 8.0
            i[k] = cos(ph).toFloat()
            q[k] = sin(ph).toFloat()
        }
        assertTrue(an.push(i, q, n))
        var best = 0
        for (k in 1 until an.size) if (an.magDb[k] > an.magDb[best]) best = k
        // Milieu (512) plus un huitieme de 1024.
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

    // ------------------------------------------------- accord fin et spectre

    /**
     * Fabrique un bloc d'IQ brut (octets non signés) contenant une porteuse
     * unique à [hz] de la fréquence d'accord.
     */
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
        // C'est le défaut qui rendait l'accord impossible sur le terrain : le
        // spectre était prélevé APRÈS le mélangeur de décalage, si bien que
        // toucher une raie la faisait fuir à deux fois l'écart touché. La raie
        // doit rester à sa place quel que soit le décalage demandé.
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
        // Le signe comptait autant que la place du mélangeur : régler « +4 kHz »
        // doit écouter quatre kilohertz AU-DESSUS de la fréquence affichée, pas
        // en dessous. On pose une raie à 4 000 Hz et une autre, plus faible, à
        // −4 000 Hz ; en BLU supérieure et avec l'accord à +4 000 Hz, seule la
        // première doit s'entendre, et à 1 500 Hz.
        val rate = Dsp.RTL_RATE.toDouble()
        val n = 262_144
        val iq = ByteArray(n * 2)
        for (k in 0 until n) {
            val p1 = 2.0 * PI * 5_500.0 * k / rate      // +4 000 + 1 500 Hz audio
            val p2 = 2.0 * PI * -5_500.0 * k / rate     // l'image, du mauvais côté
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
        // Le bouton « Crête » repose là-dessus : l'écart annoncé doit être
        // celui de la porteuse, en hertz, et pas une raie de bruit.
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
        // Fenêtre volontairement à côté de la porteuse : le résultat doit y
        // rester, sinon le bouton irait chercher une station hors écran.
        val trouve = chain.peakOffsetHz(-6_000.0, 6_000.0)
        assertTrue("crête hors fenêtre : $trouve", trouve >= -6_100.0 && trouve <= 6_100.0)
    }

    /**
     * Une modulation par déplacement de fréquence, deux bosses et rien au
     * milieu : c'est ce que rend une radiosonde, et c'est ce sur quoi la
     * recherche de crête se trompe.
     */
    private fun fsk(centreHz: Double, deviationHz: Double, n: Int,
                    amp: Double = 90.0): ByteArray {
        val rate = Dsp.RTL_RATE.toDouble()
        val iq = ByteArray(n * 2)
        var ph = 0.0
        // 4 800 bauds, le débit d'une RS41.
        val perBit = (rate / 4_800.0).toInt().coerceAtLeast(1)
        var bit = 1
        for (k in 0 until n) {
            // Autant de uns que de zéros : c'est la condition pour que les deux
            // bosses portent la même puissance et que leur milieu soit la
            // fréquence centrale. Une suite déséquilibrée déplace le centre de
            // gravité, et c'est bien ce qu'il doit faire — ce n'est simplement
            // plus la grandeur qu'on mesure ici.
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
        // L'accord automatique de la 18.5. Sur une FSK, la raie la plus forte
        // est décalée d'une excursion entière : viser la crête, c'est
        // s'accorder deux mille quatre cents hertz à côté, et une RS41 tombe
        // pour bien moins que cela. Le centre de gravité, lui, tombe entre les
        // deux bosses.
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
        // Le spectre est prélevé avant le mélangeur d'accord fin : le chiffre
        // rendu ne dépend donc pas de l'accord déjà posé. C'est ce qui rend le
        // recentrage continu inoffensif — sans cela il s'ajouterait à lui-même
        // à chaque tour et la station partirait hors bande en trois secondes.
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
        // Pas de trame de spectre, pas d'accord : zéro veut dire « je ne sais
        // pas », et l'appelant doit garder l'accord en cours plutôt que sauter
        // au milieu de la bande.
        assertEquals(0.0, RxChain().centroidOffsetHz(), 1e-9)
    }

    @Test
    fun la_desaccentuation_rattrape_le_niveau_a_mille_hertz() {
        // L'ancien facteur trois laissait 1 000 Hz onze décibels trop bas : la
        // tonalité d'appel à 1 750 Hz devenait inaudible alors que le signal
        // était parfaitement reçu. Le rattrapage est maintenant calculé.
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
        // Un émetteur à trois mètres écrase l'étage d'entrée de la clé : le
        // souffle disparaît, l'écran montre un signal fort, et il ne sort rien.
        // L'application ne peut pas le corriger, mais elle doit le dire.
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
        // Une porteuse pure ne module rien : la barre BF doit rester au
        // plancher alors que la barre HF est au maximum. C'est ce couple qui
        // distingue « pas de modulation » de « pas de signal ».
        // On mesure sur le SECOND bloc : le tout premier échantillon d'un
        // discriminateur n'a pas de précédent et produit une pointe de pleine
        // échelle, une fois pour toutes au démarrage.
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
