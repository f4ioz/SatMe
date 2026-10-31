/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import fr.f4ioz.satcombo.aprs.Adresse
import fr.f4ioz.satcombo.aprs.Ax25
import fr.f4ioz.satcombo.aprs.Trame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Made-up packet signals for the test mode, the generator and the tests:
 * AX.25 frames from a fictitious satellite (Q0SAT), a test picture in
 * SSDV packets, FSK with smooth (raised-cosine) transitions as an FM
 * transmitter's data input gives them. Never transmitted.
 */
object SignalPaquets {

    const val DEVIATION_HZ = 3_000.0
    const val INDICATIF = "Q0SAT"

    /** A telemetry UI frame of the fictitious satellite, FCS included. */
    fun trameTelemesure(numero: Int, texte: String = "SatMe test telemetry"): ByteArray =
        Ax25.encode(Trame(Adresse("CQ"), Adresse(INDICATIF, 1), emptyList(),
            "$texte #$numero batt=8.12V temp=+21C".toByteArray(Charsets.ISO_8859_1)))

    /** An SSDV packet in a UI frame (PID 0xF0), FCS included. */
    fun trameSsdv(paquet: ByteArray): ByteArray = Ax25.encode(Trame(Adresse("SSDV"), Adresse(INDICATIF, 2), emptyList(), paquet))

    /** The level (−1..1) at [t] bits into [bits]: raised-cosine edges one bit long, centred on the boundaries. */
    fun niveau(bits: IntArray, t: Double): Double {
        val k = Math.floorDiv(t.toLong(), 1L).toInt()
        fun a(i: Int) = if (i < 0 || i >= bits.size) 0.0 else if (bits[i] != 0) 1.0 else -1.0
        val f = t - k
        return if (f < 0.5) {
            val w = 0.5 + f
            a(k - 1) + (a(k) - a(k - 1)) * (0.5 - 0.5 * cos(PI * w))
        } else {
            val w = f - 0.5
            a(k) + (a(k + 1) - a(k)) * (0.5 - 0.5 * cos(PI * w))
        }
    }

    /**
     * Complex baseband at [fs]: [silenceS] of noise, then [bits] at [debit]
     * in FSK (±[deviationHz]), [decalageHz] off, Es/N0 [snrDb] measured in
     * the bit rate's bandwidth, then silence again.
     */
    fun baseband(bits: IntArray, fs: Double, debit: Int, decalageHz: Double, snrDb: Double, graine: Long = 1L,
                 silenceS: Double = 0.4, deviationHz: Double = DEVIATION_HZ): Pair<FloatArray, FloatArray> {
        val sps = fs / debit
        val avant = (silenceS * fs).toInt()
        val n = avant + (bits.size * sps).toInt() + avant
        val re = FloatArray(n); val im = FloatArray(n)
        val r = java.util.Random(graine)
        // Noise power in the whole band fs such that the bit-rate band has the asked SNR.
        val sigma = sqrt(fs / debit / Math.pow(10.0, snrDb / 10) / 2)
        var ph = 0.0
        for (i in 0 until n) {
            val t = (i - avant) / sps
            val dans = i >= avant && t < bits.size
            if (dans) {
                ph += 2 * PI * (decalageHz + deviationHz * niveau(bits, t)) / fs
                re[i] = cos(ph).toFloat(); im[i] = sin(ph).toFloat()
            }
            re[i] += (sigma * r.nextGaussian()).toFloat(); im[i] += (sigma * r.nextGaussian()).toFloat()
        }
        return re to im
    }

    /**
     * A rig's packet output in 9600 mode, as a sound card hears it (16-bit
     * mono at [fs]): [silenceS] of the discriminator's noise, then [bits] at
     * [debit] (levels ±[ampleur] full scale, raised-cosine edges), SNR
     * [snrDb] in the bit rate's bandwidth, then noise again. The rig's
     * imperfections: [inverse] (the discriminator upside down), [dc] (the
     * Doppler's rest after the CAT's step, the card's offset, full scale),
     * [horlogePpm] (the card's clock against the satellite's), and the
     * card's AC coupling (a high-pass near [passeHautHz]).
     */
    fun bandeBase(bits: IntArray, fs: Int, debit: Int, snrDb: Double, graine: Long = 1L, silenceS: Double = 0.3,
                  inverse: Boolean = false, dc: Double = 0.0, horlogePpm: Double = 0.0, ampleur: Double = 0.3,
                  passeHautHz: Double = 15.0): ShortArray {
        // The satellite's bit rate as the card's clock counts it.
        val sps = fs.toDouble() / debit / (1 + horlogePpm * 1e-6)
        val avant = (silenceS * fs).toInt()
        val n = avant + (bits.size * sps).toInt() + avant
        val r = java.util.Random(graine)
        // Noise over the whole band fs/2 such that the bit rate's band has the asked SNR.
        val sigma = ampleur * sqrt(fs / 2.0 / debit / Math.pow(10.0, snrDb / 10))
        val a = Math.exp(-2 * PI * passeHautHz / fs)
        var xAvant = 0.0; var y = 0.0
        val signe = if (inverse) -1.0 else 1.0
        return ShortArray(n) { i ->
            val t = (i - avant) / sps
            val x = (if (i >= avant && t < bits.size) signe * ampleur * niveau(bits, t) + dc else dc) + sigma * r.nextGaussian()
            // One-pole high-pass: the card's input capacitor.
            y = a * (y + x - xAvant); xAvant = x
            (y.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
        }
    }

    /** The test picture: colour bars, a grey ramp, a dark band with a white cross, 320 × 240. */
    fun mire(largeur: Int = 320, hauteur: Int = 240): IntArray {
        val barres = intArrayOf(0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00, 0xFF00FF, 0xFF0000, 0x0000FF, 0x101010)
        return IntArray(largeur * hauteur) { i ->
            val x = i % largeur; val y = i / largeur
            when {
                y < hauteur / 2 -> barres[x * 8 / largeur]
                y < hauteur * 3 / 4 -> { val v = x * 255 / (largeur - 1); (v shl 16) or (v shl 8) or v }
                kotlin.math.abs(x - largeur / 2) < 3 || kotlin.math.abs(y - hauteur * 7 / 8) < 3 -> 0xFFFFFF
                else -> 0x203048
            }
        }
    }

    /** The test picture as SSDV packets (image [numero]). */
    fun paquetsMire(numero: Int): List<ByteArray> = Ssdv.codeImage(mire(), 320, 240, INDICATIF, numero and 0xFF)
}
