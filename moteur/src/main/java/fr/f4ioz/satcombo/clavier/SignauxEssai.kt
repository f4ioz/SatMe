/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.clavier

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * PSK31 and RTTY test signals for the test generator (a fake receiver) and
 * the tests: what a station would send, as a function of time, repeated
 * with a pause. Receive side only: nothing here reaches a transmitter.
 */
object SignauxEssai {

    /**
     * PSK31 of [texte]: the idle (reversals) first, then the text, then a
     * short steady carrier, then [pauseS] of silence; repeated.
     */
    class Psk31(texte: String, private val pauseS: Double = 2.0) {
        private val bits: BooleanArray = ("0".repeat(48) + Varicode.encode(texte) + "1".repeat(16)).map { it == '1' }.toBooleanArray()
        /** The phase before each symbol (+1 / −1). */
        private val signe = DoubleArray(bits.size + 1).also { s ->
            s[0] = 1.0
            for (k in bits.indices) s[k + 1] = if (bits[k]) s[k] else -s[k]
        }
        val dureeS: Double = bits.size / DecodeurPsk31.BAUD + pauseS

        /** The signed envelope at [tS] seconds (−1..1; a reversal goes through 0 as a half cosine). */
        fun amplitude(tS: Double): Double {
            val u = tS - floor(tS / dureeS) * dureeS
            val x = u * DecodeurPsk31.BAUD
            val k = x.toInt()
            if (k >= bits.size) return 0.0
            val f = x - k
            return if (bits[k]) signe[k] else signe[k] * cos(PI * f)
        }
    }

    /**
     * RTTY of [texte] (ITA2, 1 start, 5 data, 1.5 stop bits): a second of
     * mark first, then the text, half a second of mark, [pauseS] of
     * silence; repeated.
     */
    class Rtty(texte: String, val reglages: DecodeurRtty.Reglages = DecodeurRtty.Reglages(), private val pauseS: Double = 2.0) {
        /** (end time in bits, mark) for each stretch. */
        private val fins: DoubleArray
        private val marques: BooleanArray
        val dureeS: Double

        init {
            val f = ArrayList<Double>(); val m = ArrayList<Boolean>()
            var t = 0.0
            fun met(bits: Double, marque: Boolean) { t += bits; f += t; m += marque }
            met(reglages.baud, true)
            for (c in Baudot.encode(texte, reglages.usos)) {
                met(1.0, false)
                for (b in 0 until 5) met(1.0, (c shr b) and 1 == 1)
                met(1.5, true)
            }
            met(reglages.baud / 2, true)
            fins = f.toDoubleArray(); marques = m.toBooleanArray()
            dureeS = t / reglages.baud + pauseS
        }

        /** +1 mark, −1 space, 0 silent (the pause) at [tS] seconds. */
        fun ton(tS: Double): Int {
            val u = tS - floor(tS / dureeS) * dureeS
            val b = u * reglages.baud
            if (b >= fins.last()) return 0
            var lo = 0; var hi = fins.size - 1
            while (lo < hi) { val mid = (lo + hi) / 2; if (fins[mid] <= b) lo = mid + 1 else hi = mid }
            return if (marques[lo]) 1 else -1
        }

        /** The tone's offset from the centre at [tS] (Hz; mark the upper unless reversed), or null in the pause. */
        fun decalageHz(tS: Double): Double? {
            val s = ton(tS)
            if (s == 0) return null
            return s * reglages.shiftHz / 2 * (if (reglages.inverse) -1 else 1)
        }
    }

    /** [secondes] of audio at [fs]: [psk] on [hz], peak [niveau]. */
    fun audioPsk31(psk: Psk31, fs: Int, hz: Double, secondes: Double, niveau: Double = 0.5): FloatArray =
        FloatArray((secondes * fs).toInt()) { k -> val t = k.toDouble() / fs; (niveau * psk.amplitude(t) * cos(2 * PI * hz * t)).toFloat() }

    /** [secondes] of audio at [fs]: [rtty] centred on [hz], continuous phase, peak [niveau]. */
    fun audioRtty(rtty: Rtty, fs: Int, hz: Double, secondes: Double, niveau: Double = 0.5): FloatArray {
        var ph = 0.0
        return FloatArray((secondes * fs).toInt()) { k ->
            val d = rtty.decalageHz(k.toDouble() / fs)
            if (d == null) 0f else {
                ph += 2 * PI * (hz + d) / fs
                if (ph > PI) ph -= 2 * PI
                (niveau * sin(ph)).toFloat()
            }
        }
    }
}
