/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Bell 202 AFSK at 1200 baud: mark 1200 Hz, space 2200 Hz — APRS on
 * 144.800 MHz and on the ISS digipeater (145.825 MHz).
 */
object Afsk {
    const val BAUD = 1200
    const val MARQUE = 1200.0
    const val ESPACE = 2200.0

    /**
     * Frames as 16-bit PCM at [frequence] Hz, phase-continuous, [niveau] of
     * full scale. Used by the tests, and later to transmit.
     */
    fun module(trames: List<ByteArray>, frequence: Int, niveau: Double = 0.5,
               drapeauxAvant: Int = 32): ShortArray {
        val tons = Hdlc.bits(trames, drapeauxAvant)
        val parBit = frequence.toDouble() / BAUD
        val n = (tons.size * parBit).roundToInt()
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val ton = tons[minOf((i / parBit).toInt(), tons.lastIndex)]
            phase += 2 * PI * (if (ton) MARQUE else ESPACE) / frequence
            if (phase > 2 * PI) phase -= 2 * PI
            out[i] = (sin(phase) * niveau * 32767).roundToInt().toShort()
        }
        return out
    }
}

/**
 * Received audio → AX.25 frames.
 *
 * A band-pass filter keeps 900–2500 Hz. Two quadrature detectors measure
 * the mark and space tones over one bit. The audio from a radio has lost
 * treble on the way (FM de-emphasis): the space tone arrives weaker than the
 * mark, by an amount that depends on the rig. So several slicers run side by
 * side, each weighing space against mark differently, each with its own
 * clock and HDLC decoder; a frame any of them gets right counts once.
 *
 * Works at any sample rate the recorder uses (8 to 48 kHz). Feed it the
 * capture buffers as they come; frames arrive through [surTrame].
 */
class AfskDemodulateur(
    private val frequence: Int,
    private val surTrame: (Trame) -> Unit,
) {
    private val parBit = frequence.toDouble() / Afsk.BAUD

    // Band-pass: windowed-sinc low-pass 2500 Hz minus low-pass 900 Hz.
    private val filtre: FloatArray = run {
        val m = ((frequence / 1000.0) * 3.2).roundToInt().let { if (it % 2 == 0) it + 1 else it }.coerceAtLeast(15)
        val c = (m - 1) / 2.0
        FloatArray(m) { k ->
            val x = k - c
            fun sinc(fc: Double) = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val h = sinc(2500.0 / frequence) - sinc(900.0 / frequence)
            (h * (0.42 - 0.5 * cos(2 * PI * k / (m - 1)) + 0.08 * cos(4 * PI * k / (m - 1)))).toFloat()
        }
    }
    private val historique = FloatArray(filtre.size)
    private var pos = 0

    // Detectors: mixers and one-bit moving sums.
    private val largeur = parBit.roundToInt().coerceAtLeast(2)
    private val mI = FloatArray(largeur); private val mQ = FloatArray(largeur)
    private val sI = FloatArray(largeur); private val sQ = FloatArray(largeur)
    private var sommeMI = 0f; private var sommeMQ = 0f; private var sommeSI = 0f; private var sommeSQ = 0f
    private var k = 0
    private var phaseM = 0.0; private var phaseS = 0.0
    private val pasM = 2 * PI * Afsk.MARQUE / frequence
    private val pasS = 2 * PI * Afsk.ESPACE / frequence

    /** One slicer: its own weighting, clock and HDLC state. */
    private inner class Tranche(val poids: Float) {
        var pll = 0
        var niveau = false
        var dernierBit = false
        val hdlc = HdlcDecodeur { octets -> recu(octets) }
    }
    private val tranches = listOf(0.5f, 0.71f, 1f, 1.41f, 2f, 2.83f, 4f).map { Tranche(it) }
    private val pasPll = ((1L shl 32) * Afsk.BAUD / frequence).toInt()

    // One frame heard by several slicers counts once.
    private var echantillon = 0L
    private val recents = LinkedHashMap<Int, Long>()

    /** Frames delivered since the start. */
    var nombre = 0
        private set

    /** Samples read since the start: where in the audio a frame was heard. */
    val position: Long get() = echantillon

    fun traite(pcm: ShortArray, n: Int = pcm.size) {
        for (i in 0 until n) echantillon(pcm[i] / 32768f)
    }

    private fun echantillon(x: Float) {
        echantillon++
        historique[pos] = x
        var y = 0f
        var j = pos
        for (h in filtre) {
            y += h * historique[j]
            if (--j < 0) j = historique.lastIndex
        }
        if (++pos == historique.size) pos = 0

        phaseM += pasM; if (phaseM > 2 * PI) phaseM -= 2 * PI
        phaseS += pasS; if (phaseS > 2 * PI) phaseS -= 2 * PI
        val a = (y * cos(phaseM)).toFloat(); val b = (y * sin(phaseM)).toFloat()
        val c = (y * cos(phaseS)).toFloat(); val d = (y * sin(phaseS)).toFloat()
        sommeMI += a - mI[k]; mI[k] = a
        sommeMQ += b - mQ[k]; mQ[k] = b
        sommeSI += c - sI[k]; sI[k] = c
        sommeSQ += d - sQ[k]; sQ[k] = d
        if (++k == largeur) k = 0
        val marque = sqrt(sommeMI * sommeMI + sommeMQ * sommeMQ)
        val espace = sqrt(sommeSI * sommeSI + sommeSQ * sommeSQ)

        for (t in tranches) {
            val niveau = marque > espace * t.poids
            val avant = t.pll
            t.pll += pasPll
            if (avant > 0 && t.pll < 0) {
                // Middle of a bit: NRZI, same tone as before = 1.
                t.hdlc.bit(niveau == t.dernierBit)
                t.dernierBit = niveau
            }
            if (niveau != t.niveau) {
                // A tone change marks a bit edge: pull the clock towards it,
                // harder while hunting than once flags have been seen.
                t.pll = (t.pll * if (t.hdlc.synchro) 0.74f else 0.5f).toInt()
                t.niveau = niveau
            }
        }
    }

    private fun recu(octets: ByteArray) {
        val cle = octets.contentHashCode()
        val deja = recents[cle]
        if (deja != null && echantillon - deja < frequence) return
        recents[cle] = echantillon
        if (recents.size > 64) recents.remove(recents.keys.first())
        val t = Ax25.decode(octets) ?: return
        nombre++
        surTrame(t)
    }
}
