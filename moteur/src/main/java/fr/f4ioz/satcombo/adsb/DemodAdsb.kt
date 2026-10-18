/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.adsb

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Mode S replies out of magnitude samples at 2 MS/s (two per microsecond).
 *
 * A reply starts with an 8 µs preamble — pulses of 0.5 µs at 0, 1, 3.5 and
 * 4.5 µs — then its bits in pulse position at 1 Mbit/s: a pulse in the
 * first half of the microsecond is a 1, in the second half a 0.
 *
 * At two samples per microsecond a pulse rarely falls on a sample: some
 * of it spills into the next one. The share spilled ([phase], 0 to ½) is
 * measured on the preamble's four pulses, and each bit is decided on the
 * energies weighed by it; the alignment without that correction is tried
 * as well, and the parity decides. The preamble must stand clear of the
 * samples between its pulses (where no pulse can spill), so noise alone
 * seldom starts a candidate.
 */
class DemodAdsb(
    /** A reply's address taken out of its parity: known (a DF11 or DF17 heard)? Only then are DF 0/4/5/16/20/21 kept. */
    private val connu: (Int) -> Boolean = { false },
    /** Put one wrong bit right in an extended squitter. */
    var corrige: Boolean = true,
    private val sortie: (Trame) -> Unit,
) {
    class Trame(val octets: ByteArray, val nBits: Int, val df: Int,
                /** The address recovered from the parity (formats that overlay it), else null. */
                val icaoParite: Int?,
                /** The preamble's pulses, dB below full scale. */
                val rssiDb: Double, val corrigee: Boolean,
                /** Sample index of the preamble's start in the stream. */
                val echantillon: Long) {
        fun hex(): String = octets.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    /** Candidates seen (preambles), replies kept, among them repaired, and candidates whose parity failed. */
    @Volatile var preambules = 0L; private set
    @Volatile var trames = 0L; private set
    @Volatile var corrigees = 0L; private set
    @Volatile var rejetees = 0L; private set

    /** How far above the quiet samples a preamble's pulses must stand (amplitude ratio). */
    var seuil = 2.2f

    private var tampon = FloatArray(1 shl 16)
    private var plein = 0
    /** Index in the stream of [tampon]'s first sample. */
    private var origine = 0L
    private val bits = ByteArray(14)
    private val essai = ByteArray(14)

    fun remet() { plein = 0; origine = 0L; preambules = 0; trames = 0; corrigees = 0; rejetees = 0 }

    /** Magnitude samples [mag] (0 to 1, full scale 1), [n] of them, following the previous ones. */
    fun traite(mag: FloatArray, n: Int) {
        if (plein + n > tampon.size) tampon = tampon.copyOf(Integer.highestOneBit(plein + n) * 2)
        System.arraycopy(mag, 0, tampon, plein, n)
        plein += n
        val fin = plein - FENETRE
        var i = 1
        while (i < fin) {
            val pas = candidat(i)
            i += if (pas > 0) pas else 1
        }
        // Keep what a reply starting near the end could still need (and the sample before it).
        val debut = fin - 1
        if (debut > 0) {
            val garde = plein - debut
            System.arraycopy(tampon, debut, tampon, 0, garde)
            origine += debut
            plein = garde
        }
    }

    private fun puissance(i: Int) = tampon[i] + tampon[i + 2] + tampon[i + 7] + tampon[i + 9]

    /** A reply starting at [i]: the samples it took (to skip them), else 0. */
    private fun candidat(i: Int): Int {
        val m = tampon
        val haut = puissance(i)
        if (haut <= 0f) return 0
        // Where no pulse can spill, whatever the phase.
        val calme = (m[i + 4] + m[i + 5] + m[i + 6] + m[i + 11] + m[i + 12] + m[i + 13] + m[i + 14]) / 7f
        val moy = haut / 4f
        if (moy <= calme * seuil || moy < 1e-4f) return 0
        // The best alignment of the neighbours (the next one takes over past half a sample).
        if (puissance(i - 1) > haut || puissance(i + 1) >= haut) return 0
        val pic = calme * (seuil * 0.8f)
        if (maxOf(m[i], m[i + 1]) <= pic || maxOf(m[i + 2], m[i + 3]) <= pic || maxOf(m[i + 7], m[i + 8]) <= pic || maxOf(m[i + 9], m[i + 10]) <= pic) return 0
        preambules++
        val deborde = m[i + 1] + m[i + 3] + m[i + 8] + m[i + 10]
        val phase = (deborde / (deborde + haut)).coerceIn(0f, 0.5f)
        for (p in if (phase > 0.06f) floatArrayOf(phase, 0f) else floatArrayOf(0f)) {
            val n = decode(i + 16, p) ?: continue
            trames++
            return 16 + 2 * n
        }
        rejetees++
        return 0
    }

    /** The bits from sample [d] for phase [p]; a reply whose parity holds is handed on: its length, else null. */
    private fun decode(d: Int, p: Float): Int? {
        val m = tampon
        bits.fill(0)
        val q = 1f - p
        var df = 0
        var n = ModeS.LONG
        for (k in 0 until ModeS.LONG) {
            val s = d + 2 * k
            val un = m[s] * q + m[s + 1] * p
            val zero = m[s + 1] * q + m[s + 2] * p
            if (un > zero) bits[k ushr 3] = (bits[k ushr 3].toInt() or (1 shl (7 - (k and 7)))).toByte()
            // A format nobody answers with: noise, dropped before the rest (most candidates end here).
            if (k == 4) {
                df = ModeS.bits(bits, 0, 5).toInt()
                if (df !in FORMATS) return null
                n = ModeS.longueur(df)
            }
            if (k + 1 == n) break
        }
        return when (df) {
            17, 18 -> {
                var fixe = false
                if (ModeS.syndrome(bits, n) != 0) {
                    if (!corrige) return null
                    System.arraycopy(bits, 0, essai, 0, 14)
                    if (ModeS.corrigeUnBit(essai, n) < 0 || ModeS.syndrome(essai, n) != 0) return null
                    System.arraycopy(essai, 0, bits, 0, 14)
                    fixe = true
                }
                if (fixe) corrigees++
                emet(d, n, df, null, fixe)
                n
            }
            11 -> {
                // Parity XOR interrogator code: only its low 7 bits may be set.
                if (ModeS.syndrome(bits, n) and 0xFFFF80 != 0) return null
                emet(d, n, df, null, false)
                n
            }
            0, 4, 5, 16, 20, 21 -> {
                val a = ModeS.syndrome(bits, n)
                if (!connu(a)) return null
                emet(d, n, df, a, false)
                n
            }
            else -> null
        }
    }

    private fun emet(d: Int, n: Int, df: Int, a: Int?, fixe: Boolean) {
        val i = d - 16
        val m = tampon
        val haut = (maxOf(m[i], m[i + 1]) + maxOf(m[i + 2], m[i + 3]) + maxOf(m[i + 7], m[i + 8]) + maxOf(m[i + 9], m[i + 10])) / 4.0
        sortie(Trame(bits.copyOf(n / 8), n, df, a, 20.0 * log10(maxOf(1e-6, haut)), fixe, origine + i))
    }

    companion object {
        /** The samples a long reply needs from its first: preamble, 112 bits, and the spill of the last. */
        const val FENETRE = 16 + 2 * ModeS.LONG + 4
        private val FORMATS = setOf(0, 4, 5, 11, 16, 17, 18, 20, 21)

        /** The magnitudes of 8-bit unsigned IQ pairs (the dongle's): 65 536 values, computed once. */
        private val TABLE: FloatArray by lazy {
            FloatArray(65536) { k ->
                val i = ((k ushr 8) - 127.5f) / 128f
                val q = ((k and 0xFF) - 127.5f) / 128f
                sqrt(i * i + q * q).coerceAtMost(1f)
            }
        }

        /** [n] bytes of IQ (I, Q, I, Q…) into [n]/2 magnitudes. */
        fun magnitudes8(iq: ByteArray, n: Int, out: FloatArray): Int {
            val t = TABLE
            var j = 0
            var k = 0
            while (k + 1 < n) {
                out[j++] = t[((iq[k].toInt() and 0xFF) shl 8) or (iq[k + 1].toInt() and 0xFF)]
                k += 2
            }
            return j
        }

        /** [n] 16-bit values (I, Q…) of full scale [echelle] into [n]/2 magnitudes. */
        fun magnitudes16(iq: ShortArray, n: Int, out: FloatArray, echelle: Float = 2048f): Int {
            var j = 0
            var k = 0
            while (k + 1 < n) {
                val i = iq[k] / echelle; val q = iq[k + 1] / echelle
                out[j++] = sqrt(i * i + q * q).coerceAtMost(1f)
                k += 2
            }
            return j
        }
    }
}
