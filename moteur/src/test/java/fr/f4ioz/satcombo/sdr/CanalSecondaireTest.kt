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
import fr.f4ioz.satcombo.domain.Ft8
import fr.f4ioz.satcombo.domain.Ft8Decodeur
import fr.f4ioz.satcombo.domain.Ft8Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Secondary channels fed from the same IQ as the main receiver: each one
 * hears its own station and nothing of the others.
 */
class CanalSecondaireTest {

    private val brut = Dsp.RTL_RATE.toDouble()

    /** The CW keying of [texte]: (key down, ms) with silence around. */
    private fun manip(texte: String, wpm: Int) = listOf(false to 500.0) + DecodeurCw.manipulation(texte, wpm) + listOf(false to 1200.0)

    /** Key state at time [t] (s) for a keying list, soft 4 ms edges. */
    private fun enveloppe(m: List<Pair<Boolean, Double>>): (Double) -> Double {
        val bords = DoubleArray(m.size + 1)
        for (i in m.indices) bords[i + 1] = bords[i] + m[i].second / 1000.0
        return { t ->
            var i = bords.indexOfLast { it <= t }.coerceIn(0, m.size - 1)
            if (!m[i].first) 0.0 else minOf(1.0, (t - bords[i]) / 0.004, (bords[i + 1] - t) / 0.004).coerceAtLeast(0.0)
        }
    }

    private fun duree(m: List<Pair<Boolean, Double>>) = m.sumOf { it.second } / 1000.0

    /** Strength of [hz] in [pcm] (Goertzel, normalised). */
    private fun puissance(pcm: ShortArray, n: Int, hz: Double, rate: Double): Double {
        val w = 2 * PI * hz / rate
        var re = 0.0; var im = 0.0
        for (k in 0 until n) { re += pcm[k] * cos(w * k); im -= pcm[k] * sin(w * k) }
        return sqrt(re * re + im * im) / n
    }

    private fun decodeCw(pcm: ShortArray, n: Int): String {
        val sb = StringBuilder()
        val d = DecodeurCw(Dsp.AUDIO_RATE, { sb.append(it) })
        var i = 0
        while (i < n) { val k = minOf(2048, n - i); d.ecoute(pcm.copyOfRange(i, i + k), k); i += k }
        return sb.toString().trim()
    }

    /**
     * The main chain listens to a USB tone 1 kHz above +5 kHz; a CW station
     * keys a note at +20.7 kHz (near path) and another at −180.7 kHz (far
     * path, outside the main chain's first stage). Each channel decodes its
     * own text, and the main audio stays the tone.
     */
    @Test fun `deux canaux CW et la voie principale, chacun son signal`() {
        val mA = manip("CQ CQ DE F4IOZ K", 22)
        val mB = manip("VVV TEST F5ABC", 18)
        val envA = enveloppe(mA); val envB = enveloppe(mB)
        val seconds = maxOf(duree(mA), duree(mB))
        val total = (seconds * brut).toInt()

        val chaine = RxChain()
        chaine.mode = RxMode.USB
        chaine.offsetHz = 5_000.0
        val canaux = CanauxSecondaires()
        val cibles = listOf(
            CanauxSecondaires.Cible(1, 20_000.0, ModeCanal.CW),
            CanauxSecondaires.Cible(2, -181_400.0, ModeCanal.CW),
        )
        val sortie = HashMap<Int, ArrayList<Short>>()
        chaine.canaux = { aI, aQ, n, bI, bQ, n1 ->
            canaux.traite(aI, aQ, n, bI, bQ, n1, cibles) { id, pcm, k -> val l = sortie.getOrPut(id) { ArrayList() }; for (i in 0 until k) l += pcm[i] }
        }

        val bloc = 2 * 16_384
        val iq = ShortArray(bloc)
        val audio = ShortArray(chaine.maxAudio(bloc * 2))
        val principal = ArrayList<Short>()
        val rnd = java.util.Random(3)
        var k = 0
        while (k < total) {
            val paires = minOf(bloc / 2, total - k)
            for (j in 0 until paires) {
                val t = (k + j) / brut
                var i = 0.0; var q = 0.0
                // Main: a steady tone at +6 kHz (USB 1 kHz above +5 kHz).
                val p0 = 2 * PI * 6_000.0 * t; i += 0.05 * cos(p0); q += 0.05 * sin(p0)
                // CW A at +20.7 kHz, CW B at −180.7 kHz.
                val a = envA(t) * 0.05; val pa = 2 * PI * 20_700.0 * t; i += a * cos(pa); q += a * sin(pa)
                val b = envB(t) * 0.05; val pb = 2 * PI * -180_700.0 * t; i += b * cos(pb); q += b * sin(pb)
                i += 0.004 * rnd.nextGaussian(); q += 0.004 * rnd.nextGaussian()
                iq[2 * j] = (i * 32767).toInt().toShort(); iq[2 * j + 1] = (q * 32767).toInt().toShort()
            }
            val n = chaine.process16(iq, paires * 2, audio)
            for (j in 0 until n) principal += audio[j]
            k += paires
        }

        val a = sortie[1]!!.toShortArray(); val b = sortie[2]!!.toShortArray()
        assertTrue(decodeCw(a, a.size), decodeCw(a, a.size).endsWith("CQ DE F4IOZ K"))
        assertTrue(decodeCw(b, b.size), decodeCw(b, b.size).endsWith("TEST F5ABC"))
        val etats = canaux.etats()
        assertTrue("canal 1 par la voie proche", etats[1]!!.parLeLoin.not())
        assertTrue("canal 2 par la voie lointaine", etats[2]!!.parLeLoin)

        // The main audio is the 1 kHz tone, not the CW notes.
        val p = principal.toShortArray()
        val ton = puissance(p, p.size, 1_000.0, Dsp.AUDIO_RATE.toDouble())
        val note = puissance(p, p.size, 700.0, Dsp.AUDIO_RATE.toDouble())
        assertTrue("ton $ton note $note", ton > 20 * note)
        // And the CW channel does not hear the main tone (it sits 14.7 kHz away).
        val fuite = puissance(a, a.size, 1_000.0, Dsp.AUDIO_RATE.toDouble())
        val sienne = puissance(a, a.size, 700.0, Dsp.AUDIO_RATE.toDouble())
        assertTrue("sienne $sienne fuite $fuite", sienne > 20 * fuite)
    }

    /** An FT8 message on a dial 25 kHz below the PLL, decoded from the channel's audio. */
    @Test fun `un canal FT8 decode son message`() {
        val audio12 = ft8("CQ", "F4IOZ", "JN18", 1_200.0)
        val mode = Ft8Signal.FT8
        val e1 = brut / Dsp.DECIM_1
        val total = (audio12.size / mode.cadenceHz * e1).toInt()
        val canal = CanalSecondaire()
        canal.mode = ModeCanal.USB
        canal.decalageHz = -25_000.0
        val bloc = 4096
        val eI = FloatArray(bloc); val eQ = FloatArray(bloc)
        val out = ShortArray(canal.maxSortie(bloc * Dsp.DECIM_1))
        val sortie = ArrayList<Float>()
        val rnd = java.util.Random(5)
        var k = 0
        while (k < total) {
            val n = minOf(bloc, total - k)
            for (j in 0 until n) {
                val t = (k + j) / e1
                // DSB on the dial: the upper sideband is the FT8 audio, the lower one its mirror (filtered out).
                val x = interpole(audio12, t * mode.cadenceHz)
                val ph = 2 * PI * -25_000.0 * t
                // Another station 3 kHz higher, a steady carrier: must not get in.
                val ph2 = 2 * PI * -22_000.0 * t
                eI[j] = (x * cos(ph) + 0.3 * cos(ph2) + 0.01 * rnd.nextGaussian()).toFloat()
                eQ[j] = (x * sin(ph) + 0.3 * sin(ph2) + 0.01 * rnd.nextGaussian()).toFloat()
            }
            val p = canal.traite(FloatArray(0), FloatArray(0), 0, eI, eQ, n, out)
            for (j in 0 until p) sortie += out[j] / 32768f
            k += n
        }
        val r = Ft8Decodeur.decode(sortie.toFloatArray(), Dsp.AUDIO_RATE.toDouble(), mode)
        assertEquals(r.joinToString { it.message.brut }, "CQ F4IOZ JN18", r.firstOrNull()?.message?.brut)
        assertEquals(1_200.0, r[0].frequenceHz, 10.0)
    }

    /**
     * The cost of a channel at the receiver's raw rate, near and far, printed
     * in ns per raw sample and as a share of one core. Target: under 3 %.
     */
    @Test fun `le cout dun canal par echantillon`() {
        val bloc = 16_384
        val aI = FloatArray(bloc); val aQ = FloatArray(bloc)
        val rnd = java.util.Random(1)
        for (k in 0 until bloc) { aI[k] = rnd.nextGaussian().toFloat() * 0.1f; aQ[k] = rnd.nextGaussian().toFloat() * 0.1f }
        val chaine = RxChain()
        val dec1 = ComplexDecimator(Dsp.lowPass(95, brut / Dsp.DECIM_1 / 2.4, brut), Dsp.DECIM_1)
        val bI = FloatArray(dec1.maxOut(bloc)); val bQ = FloatArray(dec1.maxOut(bloc))
        val n1 = dec1.process(aI, aQ, bloc, bI, bQ)
        check(chaine.spectrumSpanHz > 0)
        for ((nom, dec, mode) in listOf(Triple("proche USB", 20_000.0, ModeCanal.USB), Triple("proche CW", 20_000.0, ModeCanal.CW),
            Triple("loin USB", 200_000.0, ModeCanal.USB), Triple("proche FM", 20_000.0, ModeCanal.FM))) {
            val c = CanalSecondaire(); c.mode = mode; c.decalageHz = dec
            val out = ShortArray(c.maxSortie(bloc))
            repeat(200) { c.traite(aI, aQ, bloc, bI, bQ, n1, out) }   // warm-up (JIT)
            val tours = 400
            val t0 = System.nanoTime()
            repeat(tours) { c.traite(aI, aQ, bloc, bI, bQ, n1, out) }
            val ns = (System.nanoTime() - t0).toDouble() / (tours.toLong() * bloc)
            val part = ns * brut / 1e9 * 100
            println("Canal secondaire $nom : %.2f ns/échantillon brut, %.2f %% d'un cœur à %.0f S/s".format(ns, part, brut))
            // Loose bound for a busy test machine; the printed figure is the measurement.
            assertTrue("$nom $part %", part < 15.0)
        }
    }

    // ------------------------------------------------------------ helpers

    private fun interpole(a: FloatArray, x: Double): Double {
        val i = x.toInt()
        if (i < 0 || i + 1 >= a.size) return 0.0
        val f = x - i
        return a[i] * (1 - f) + a[i + 1] * f
    }

    /** A type 1 message as FT8 audio at the mode's own rate (parity as the encoder makes it). */
    private fun ft8(appele: String, appelant: String, carre: String, hz: Double): FloatArray {
        val c1 = if (appele == "CQ") 2L else Ft8.indicatifVers28(appele)!!
        val c2 = Ft8.indicatifVers28(appelant)!!
        val g15 = (carre[0] - 'A') * 1800 + (carre[1] - 'A') * 100 + (carre[2] - '0') * 10 + (carre[3] - '0')
        val message = BooleanArray(Ft8.BITS_MESSAGE)
        Ft8.ecritEntier(message, 0, 28, c1)
        Ft8.ecritEntier(message, 29, 28, c2)
        Ft8.ecritEntier(message, 59, 15, g15.toLong())
        Ft8.ecritEntier(message, 74, 3, 1L)
        val utiles = Ft8.avecControle(message)
        val bits = fr.f4ioz.satcombo.domain.Ldpc.encode(utiles)
        return Ft8Signal.synthetise(Ft8.bitsVersSymboles(bits), Ft8Signal.FT8, hz, 0.5, amplitude = 0.3f)
    }
}
