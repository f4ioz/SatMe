/*
 * TellusMe — the SatMe ecosystem's terrestrial station
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.tellus

import fr.f4ioz.satcombo.sdr.ComplexBandpass
import fr.f4ioz.satcombo.sdr.ComplexDecimator
import fr.f4ioz.satcombo.sdr.Dsp
import fr.f4ioz.satcombo.sdr.hpsdr.HpsdrProtocole
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The Brick2's audio: the signal at the centre of the span, demodulated to
 * 12 kHz mono 16-bit with SatMe's DSP blocks ([ComplexDecimator],
 * [ComplexBandpass], [Dsp.lowPass]). Down to 12 kHz from the radio's 48–384
 * kHz in one decimating filter, then USB/LSB (the phasing band-pass,
 * 300–2700 Hz), CW (400 Hz around +700 Hz: the operator hears a 700 Hz note),
 * AM (envelope) or FM (discriminator); a slow AGC, then the volume.
 * Ported from the owner's own panadapter code.
 */
class DemodBrick(debit: Int) {

    var mode: String = "USB"; private set
    var volume: Float = 0.6f; private set
    var debit: Int = debit; private set

    private lateinit var decim: ComplexDecimator
    private var filtre: ComplexBandpass? = null
    private var dc = 0.0
    private var prevI = 1f; private var prevQ = 0f
    private var agc = 1.0
    private var bi = FloatArray(0); private var bq = FloatArray(0); private var ba = FloatArray(0)

    init { construit() }

    @Synchronized fun regle(mode: String? = null, volume: Float? = null, debit: Int? = null) {
        var refaire = false
        if (mode != null && mode in MODES && mode != this.mode) { this.mode = mode; refaire = true }
        if (debit != null && debit in HpsdrProtocole.CODES_DEBIT && debit != this.debit) { this.debit = debit; refaire = true }
        if (volume != null && !volume.isNaN()) this.volume = volume.coerceIn(0f, 1f)
        if (refaire) construit()
    }

    private fun construit() {
        val f = debit / AUDIO
        val taps = min(1025, 32 * f + 1)
        decim = ComplexDecimator(Dsp.lowPass(taps, 5_500.0, debit.toDouble()), f)
        filtre = when (mode) {
            "USB" -> ComplexBandpass(129, 1500.0, 2400.0, AUDIO.toDouble())
            "LSB" -> ComplexBandpass(129, -1500.0, 2400.0, AUDIO.toDouble())
            "CW" -> ComplexBandpass(129, 700.0, 400.0, AUDIO.toDouble())
            else -> null
        }
        dc = 0.0; prevI = 1f; prevQ = 0f
    }

    /** [n] complex samples ([iq], I and Q interleaved, full scale ±2²³) → 16-bit little-endian PCM at 12 kHz. */
    @Synchronized fun traite(iq: FloatArray, n: Int): ByteArray {
        if (bi.size < n) { bi = FloatArray(n); bq = FloatArray(n) }
        for (k in 0 until n) { bi[k] = iq[2 * k] / PLEINE_ECHELLE; bq[k] = iq[2 * k + 1] / PLEINE_ECHELLE }
        val m = decim.maxOut(n)
        val oi = FloatArray(m); val oq = FloatArray(m)
        val c = decim.process(bi, bq, n, oi, oq)
        if (c == 0) return ByteArray(0)
        if (ba.size < c) ba = FloatArray(c)
        when (mode) {
            "AM" -> {
                var somme = 0.0
                for (k in 0 until c) { val e = sqrt((oi[k] * oi[k] + oq[k] * oq[k]).toDouble()); ba[k] = e.toFloat(); somme += e }
                dc = 0.99 * dc + 0.01 * (somme / c)
                for (k in 0 until c) ba[k] = (ba[k] - dc).toFloat()
            }
            "FM" -> for (k in 0 until c) {
                val re = oi[k] * prevI + oq[k] * prevQ; val im = oq[k] * prevI - oi[k] * prevQ
                ba[k] = (atan2(im.toDouble(), re.toDouble()) / PI).toFloat(); prevI = oi[k]; prevQ = oq[k]
            }
            else -> filtre!!.process(oi, oq, c, ba)
        }
        if (mode != "FM") {
            var e = 0.0
            for (k in 0 until c) e += ba[k] * ba[k]
            val rms = sqrt(e / c) + 1e-9
            agc += 0.08 * (min(60.0, 0.18 / rms) - agc)
        }
        val g = (if (mode == "FM") 1.0 else agc) * volume
        val out = ByteArray(2 * c)
        for (k in 0 until c) {
            val v = (ba[k] * g).coerceIn(-1.0, 1.0)
            val s = (v * 32767).toInt()
            out[2 * k] = (s and 0xFF).toByte(); out[2 * k + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    companion object {
        const val AUDIO = 12_000
        const val PLEINE_ECHELLE = 8_388_608f
        val MODES = setOf("USB", "LSB", "CW", "AM", "FM")
    }
}
