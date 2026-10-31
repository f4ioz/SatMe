/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ft

import fr.f4ioz.satcombo.domain.Ft8Onde
import fr.f4ioz.satcombo.domain.Ft8Signal
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A pass's recording made up for the tests: FT4 / FT8 transmissions (SatMe's own encoder, `Ft8Onde.tons`) on
 * their UTC slots, in white noise, with what a LEO pass does to them — each station's own drift, and the whole
 * audio's offset ([decalage]: the CAT's Doppler steps, a sawtooth). The messages are the kind heard on RS-44 on
 * 27 October 2026, re-synthesised; no sound of the station is in the tests.
 */
object EnregistrementFt {

    /** One transmission: its text, mode, its slot's start (UTC ms), audio frequency, SNR (2500 Hz), start in the slot, own drift. */
    class Emission(val texte: String, val mode: String, val trancheMs: Long, val hz: Double, val snr: Double,
                   val dtS: Double = 0.5, val penteHzS: Double = 0.0)

    /** Noise of unit deviation, then everything times this (16-bit WAV-safe). */
    const val ECHELLE = 0.05f

    /**
     * [dureeS] of sound at [cadence] from [origineMs] (UTC); [decalage] (s since the start → Hz) moves all signals.
     */
    fun fabrique(origineMs: Long, dureeS: Double, cadence: Int, emissions: List<Emission>, graine: Long = 1,
                 decalage: (Double) -> Double = { 0.0 }): FloatArray {
        val n = (dureeS * cadence).toInt()
        val r = java.util.Random(graine)
        val out = FloatArray(n) { r.nextGaussian().toFloat() }
        val bruit2500 = 2500.0 / (cadence / 2.0)
        for (e in emissions) {
            val ft4 = e.mode == "FT4"
            val mode = if (ft4) Ft8Signal.FT4 else Ft8Signal.FT8
            val tons = Ft8Onde.tons(e.texte, ft4) ?: error("message non codable : ${e.texte}")
            val f = Ft8Signal.trajectoire(tons, mode, e.hz, mode.lissageBT, e.penteHzS)
            val a = sqrt(2.0 * bruit2500 * 10.0.pow(e.snr / 10.0))
            val debutS = (e.trancheMs - origineMs) / 1000.0 + e.dtS
            val i0 = Math.ceil(debutS * cadence).toInt().coerceAtLeast(0)
            val i1 = minOf(n, ((debutS + mode.dureeS) * cadence).toInt())
            var ph = 0.0
            for (i in i0 until i1) {
                val tS = i.toDouble() / cadence
                val k = ((tS - debutS) * mode.cadenceHz).toInt().coerceIn(0, f.size - 1)
                // Edges ramped over a symbol, as a transmitter does.
                val bord = minOf(1.0, (tS - debutS) / mode.dureeSymbole, (debutS + mode.dureeS - tS) / mode.dureeSymbole).coerceAtLeast(0.0)
                out[i] += (a * bord * sin(ph)).toFloat()
                ph += 2 * PI * (f[k] + decalage(tS)) / cadence
            }
        }
        for (i in out.indices) out[i] *= ECHELLE
        return out
    }

    /** The CAT's Doppler: the audio slides at [vitesseHzS] and is pulled back by [pasHz] each time it is that far. */
    fun dents(vitesseHzS: Double, pasHz: Double, phaseS: Double = 0.0): (Double) -> Double {
        val periode = pasHz / kotlin.math.abs(vitesseHzS)
        return { t -> vitesseHzS * (((t + phaseS) % periode) - periode / 2) }
    }

    /** 16-bit mono PCM WAV, as SatMe-PC records. */
    fun wav(f: java.io.File, audio: FloatArray, cadence: Int) {
        val b = java.nio.ByteBuffer.allocate(44 + audio.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + audio.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(cadence).putInt(cadence * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(audio.size * 2)
        for (v in audio) b.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        f.writeBytes(b.array())
    }
}
