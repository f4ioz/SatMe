/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import fr.f4ioz.satcombo.sdr.Dsp
import fr.f4ioz.satcombo.sdr.ModulateurAudio
import fr.f4ioz.satcombo.sdr.ModulationAudio
import fr.f4ioz.satcombo.sdr.RxChain
import fr.f4ioz.satcombo.sdr.RxMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * End to end, offline: a picture modulated as the transmitter sends it
 * ([ModulateurAudio] at the receiver's rate, noise added), demodulated by
 * SatMe's own receive chain ([RxChain]), decoded by [SstvDecoder]; the
 * picture compared with the one sent.
 */
class SstvRadioBoucleTest {

    private val fa = Dsp.AUDIO_RATE
    private val l = Dsp.RTL_RATE / fa

    /** Bars on the top half, a grey ramp below, the callsign band over it all. */
    private fun mire(m: SstvMode): IntArray {
        val bars = intArrayOf(0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00, 0xFF00FF, 0xFF0000, 0x0000FF, 0x000000)
        val px = IntArray(m.width * m.height) { i ->
            val x = i % m.width; val y = i / m.width
            if (y < m.height / 2) bars[x * 8 / m.width] else { val v = x * 255 / (m.width - 1); (v shl 16) or (v shl 8) or v }
        }
        return IndicatifSstv.incruste(px, m.width, m.height, "F4IOZ")
    }

    private class Recu(var mode: SstvMode? = null, var px: IntArray = IntArray(0), var lignes: Int = 0, var complete: Boolean = false)

    /** Modulated, with complex noise ([snrDb] in the channel), through the chain in [rx], decoded. */
    private fun boucle(audio: (ShortArray) -> Int, mod: ModulationAudio, rx: RxMode, snrDb: Double = 30.0, decalageHz: Double = 0.0): Recu {
        val m = ModulateurAudio(mod, fa, l, 5_000.0, source = audio)
        val chaine = RxChain(Dsp.RTL_RATE.toDouble()).apply { mode = rx; offsetHz = decalageHz }
        val r = Recu()
        val dec = SstvDecoder(fa, object : SstvDecoder.Listener {
            override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) {
                if (r.mode == null) { r.mode = mode; r.px = pixels.copyOf(); r.lignes = linesDone; r.complete = complete }
            }
        })
        val paires = 65_536
        val iq = ShortArray(2 * paires)
        val son = ShortArray(chaine.maxAudio(4 * paires))
        // Noise over the channel: the signal's power (0.5 of full scale) less [snrDb] in 12 kHz, spread over the full rate.
        val sigma = 0.5 * 32752 * Math.pow(10.0, -snrDb / 20) * Math.sqrt(Dsp.RTL_RATE / 12_000.0 / 2)
        val alea = Random(3)
        var fin = 0
        while (fin < 40) {
            m.remplis(iq)
            for (k in iq.indices) iq[k] = (iq[k] + alea.nextGaussian() * sigma).roundToInt().coerceIn(-32767, 32767).toShort()
            val n = chaine.process16(iq, iq.size, son)
            dec.feed(son, n)
            if (m.fini) fin++
        }
        dec.finish()
        return r
    }

    /** Mean absolute error per channel (0–255) over the rows [r0, r1). */
    private fun ecart(m: SstvMode, a: IntArray, b: IntArray, r0: Int, r1: Int): Double {
        var s = 0L; var n = 0
        for (y in r0 until r1) for (x in 0 until m.width) {
            val p = a[y * m.width + x]; val q = b[y * m.width + x]
            for (sh in intArrayOf(16, 8, 0)) { s += abs(((p shr sh) and 255) - ((q shr sh) and 255)); n++ }
        }
        return s.toDouble() / n
    }

    private fun verifie(nom: String, mod: ModulationAudio, rx: RxMode, snr: Double, max: Double, decalageHz: Double = 0.0) {
        val m = SstvMode.byName(nom)!!
        val src = mire(m)
        val r = boucle(ProgrammeSstv(listOf(m to src), fa, cw = "DE F4IOZ"), mod, rx, snr, decalageHz)
        assertEquals(m, r.mode)
        assertTrue("${r.lignes}/${m.height}", r.complete && r.lignes == m.height)
        // The edges (first lines, the band's border) aside: the picture's bulk.
        val e = ecart(m, src, r.px, 2, m.height - 2)
        val eBande = ecart(m, src, r.px, 1, IndicatifSstv.hauteurBande(m.height) - 1)
        println("%s %s → %s, %.0f dB : écart moyen %.1f / 255 (bandeau %.1f)".format(nom, mod, rx, snr, e, eBande))
        assertTrue("$nom $mod : écart $e", e <= max)
    }

    @Test
    fun robot36_en_fm_etroite_decode_par_la_chaine_nfm() = verifie("Robot 36", ModulationAudio.NBFM, RxMode.NFM, 25.0, 12.0)

    @Test
    fun robot36_en_blu_haute_decode_par_la_chaine_usb() = verifie("Robot 36", ModulationAudio.USB, RxMode.USB, 25.0, 12.0)

    @Test
    fun martin_m2_en_blu_basse_decode_par_la_chaine_lsb() = verifie("Martin M2", ModulationAudio.LSB, RxMode.LSB, 25.0, 12.0)

    @Test
    fun chaque_mode_est_reconnu_par_son_en_tete_vis() {
        // The header and the first blocks of every mode, in FM: the decoder names the mode.
        for (m in SstvMode.ALL) {
            val s = SstvEncoder.Source(m, mire(m), fa, blocks = 6)
            val r = boucle({ b -> s.read(b) }, ModulationAudio.NBFM, RxMode.NFM, 25.0)
            assertEquals(m.name, r.mode?.name)
            assertTrue("${m.name} : ${r.lignes} lignes", r.lignes >= 4)
        }
    }
}
