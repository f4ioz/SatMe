/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** What a secondary channel demodulates. */
enum class ModeCanal {
    /** Upper sideband, 200 to 3 000 Hz: voice, FT8/FT4, SSTV. */
    USB,

    /** Upper sideband through a 500 Hz filter on a 700 Hz note. */
    CW,

    /** Narrowband FM. */
    FM
}

/**
 * A sub-receiver: one more station heard from the IQ blocks the main
 * receiver already has, with its own mixer, decimators and demodulator.
 *
 *   near (|offset| ≤ [PROCHE_HZ]): the main chain's 176 400 S/s output
 *        mixed down, then  /4 → 44 100  /4 → 11 025  sideband filter
 *   far (anywhere in the raw band): the raw 1 058 400 S/s stream mixed down,
 *        a short /6 decimator of its own, then the same
 *
 * The near path rides on the main chain's first decimator (free); the far
 * path pays its own, so a channel reaches the whole band the receiver sees
 * (the whole narrowband transponder), not only the part around the tuning.
 * The selectivity happens at 11 025 S/s where a sharp filter is cheap; the
 * audio is brought back to [Dsp.AUDIO_RATE] for the decoders and the speaker.
 * FM stays at 44 100 (its channel is wider than 11 kHz allows).
 *
 * No Android, no allocation per block once warmed up.
 */
class CanalSecondaire(private val debitBrut: Double = Dsp.RTL_RATE.toDouble()) {

    /** Rate of the main chain's first stage (the near path's input). */
    val debitEtage1 = debitBrut / Dsp.DECIM_1
    private val debitAudio = debitEtage1 / Dsp.DECIM_2
    private val debitBande = debitAudio / DECIM_BANDE

    /** Offset of the station's dial from the receiver's PLL, Hz (in the raw band). */
    @Volatile var decalageHz: Double = 0.0

    @Volatile var mode: ModeCanal = ModeCanal.USB

    // Far path: wide and short, it only has to keep ±6 kHz clean after mixing
    // (aliases come from ±176.4 kHz away): 47 taps are plenty.
    private val dec0 = ComplexDecimator(Dsp.lowPass(47, debitBrut / 12.0, debitBrut), Dsp.DECIM_1)
    private val decA = ComplexDecimator(Dsp.lowPass(63, 12_000.0, debitEtage1), Dsp.DECIM_2)
    // Down to 11 025: flat to 3 kHz, aliases (≥ 8 kHz) rejected.
    private val decB = ComplexDecimator(Dsp.lowPass(55, 5_500.0, debitAudio), DECIM_BANDE)
    private var filtre: ComplexBandpass? = null
    private var filtreMode: ModeCanal? = null
    private val agc = AudioAgc(rate = debitBande)
    private val interp = Interpolateur(DECIM_BANDE, Dsp.lowPass(4 * 16 - 1, 5_500.0, debitAudio))
    private val fm = FmDiscriminator(debitAudio, 5_000.0)
    private val dc = DcBlock()

    private var loin = false
    private var c = 1.0
    private var s = 0.0

    private var mI = FloatArray(0); private var mQ = FloatArray(0)
    private var zI = FloatArray(0); private var zQ = FloatArray(0)
    private var aI = FloatArray(0); private var aQ = FloatArray(0)
    private var bI = FloatArray(0); private var bQ = FloatArray(0)
    private var fl = FloatArray(0)
    private var bande = ShortArray(0)

    /** In-channel level of the last block (±5.5 kHz around the dial), dBFS. */
    @Volatile var niveauDb: Float = -120f
        private set

    /** Output peak of the last block, 0..1. */
    @Volatile var crete: Float = 0f
        private set

    /** True when the last block came by the far path (its own decimator). */
    val parLeLoin: Boolean get() = loin

    fun reinitialise() {
        dec0.reset(); decA.reset(); decB.reset(); filtre?.reset(); agc.reset(); interp.reset(); fm.reset(); dc.reset()
        c = 1.0; s = 0.0; niveauDb = -120f; crete = 0f
    }

    /** Audio samples at most for [nBrut] raw input pairs. */
    fun maxSortie(nBrut: Int): Int = nBrut / (Dsp.DECIM_1 * Dsp.DECIM_2) + 2 * DECIM_BANDE + 8

    /**
     * One block: [brutI]/[brutQ] ([nBrut]) the raw stream, [e1I]/[e1Q] ([n1])
     * the main chain's first-stage output of the same block. Neither is
     * modified. Writes [Dsp.AUDIO_RATE] audio into [out], returns its count.
     */
    fun traite(brutI: FloatArray, brutQ: FloatArray, nBrut: Int,
               e1I: FloatArray, e1Q: FloatArray, n1: Int, out: ShortArray): Int {
        val d = decalageHz
        // Hysteresis: a channel on the border does not flap from path to path.
        val veutLoin = if (loin) abs(d) > PROCHE_HZ - 2_000.0 else abs(d) > PROCHE_HZ
        if (veutLoin != loin) { loin = veutLoin; dec0.reset(); c = 1.0; s = 0.0 }

        val srcI: FloatArray; val srcQ: FloatArray; val n0: Int
        if (loin) {
            melange(brutI, brutQ, nBrut, d, debitBrut)
            val m = dec0.maxOut(nBrut)
            if (zI.size < m) { zI = FloatArray(m); zQ = FloatArray(m) }
            n0 = dec0.process(mI, mQ, nBrut, zI, zQ)
            srcI = zI; srcQ = zQ
        } else {
            melange(e1I, e1Q, n1, d, debitEtage1)
            srcI = mI; srcQ = mQ; n0 = n1
        }

        val ma = decA.maxOut(n0)
        if (aI.size < ma) { aI = FloatArray(ma); aQ = FloatArray(ma) }
        val na = decA.process(srcI, srcQ, n0, aI, aQ)
        if (na <= 0) return 0

        if (mode == ModeCanal.FM) {
            val count = minOf(na, out.size)
            niveauDb = niveau(aI, aQ, count)
            val p = fm.process(aI, aQ, count, out)
            dc.process(out, p)
            crete = crete(out, p)
            return p
        }

        val mb = decB.maxOut(na)
        if (bI.size < mb) { bI = FloatArray(mb); bQ = FloatArray(mb) }
        val nb = decB.process(aI, aQ, na, bI, bQ)
        if (nb <= 0) return 0
        niveauDb = niveau(bI, bQ, nb)
        val f = filtre?.takeIf { filtreMode == mode } ?: when (mode) {
            ModeCanal.CW -> ComplexBandpass(TAPS_BANDE, 700.0, 500.0, debitBande)
            else -> ComplexBandpass(TAPS_BANDE, 1_600.0, 2_800.0, debitBande)
        }.also { filtre = it; filtreMode = mode }
        if (fl.size < nb) fl = FloatArray(nb)
        f.process(bI, bQ, nb, fl)
        if (bande.size < nb) bande = ShortArray(nb)
        agc.process(fl, nb, bande)
        val p = interp.process(bande, nb, out)
        crete = crete(out, p)
        return p
    }

    /** Mixes [n] samples by −[hz] at [debit] into [mI], [mQ]: the dial lands on 0 Hz. */
    private fun melange(inI: FloatArray, inQ: FloatArray, n: Int, hz: Double, debit: Double) {
        if (mI.size < n) { mI = FloatArray(n); mQ = FloatArray(n) }
        // A rotating phasor rather than cos/sin per sample; renormalised each block.
        val w = 2.0 * Math.PI * hz / debit
        val pr = kotlin.math.cos(w); val pi = kotlin.math.sin(w)
        var cc = c; var ss = s
        for (k in 0 until n) {
            val i = inI[k]; val q = inQ[k]
            val cf = cc.toFloat(); val sf = ss.toFloat()
            mI[k] = i * cf + q * sf
            mQ[k] = q * cf - i * sf
            val nc = cc * pr - ss * pi
            ss = cc * pi + ss * pr
            cc = nc
        }
        val m = 1.0 / sqrt(cc * cc + ss * ss)
        c = cc * m; s = ss * m
    }

    private fun niveau(i: FloatArray, q: FloatArray, n: Int): Float {
        if (n <= 0) return niveauDb
        var mag = 0.0
        for (k in 0 until n) mag += sqrt((i[k] * i[k] + q[k] * q[k]).toDouble())
        val avg = mag / n
        return if (avg <= 1e-9) -120f else (20.0 * log10(avg)).toFloat()
    }

    private fun crete(b: ShortArray, n: Int): Float {
        var mx = 0
        for (k in 0 until n) { val v = abs(b[k].toInt()); if (v > mx) mx = v }
        return mx / 32768f
    }

    companion object {
        /** Within this of the PLL the main chain's first stage is flat: the near path. */
        const val PROCHE_HZ = 36_000.0
        /** How far a channel may sit from the PLL: the raw band less the tuner's soft edges. */
        const val PORTEE_HZ = 505_000.0
        private const val DECIM_BANDE = 4
        private const val TAPS_BANDE = 191
    }
}

/**
 * Integer-factor interpolator (polyphase FIR): [facteur] outputs per input,
 * the low-pass [taps] designed at the output rate; gain restored.
 */
class Interpolateur(private val facteur: Int, taps: FloatArray) {
    private val parPhase = (taps.size + facteur - 1) / facteur
    /** Phase p, tap k: taps[p + k·facteur] × facteur. */
    private val h = Array(facteur) { p -> FloatArray(parPhase) { k -> (taps.getOrElse(p + k * facteur) { 0f }) * facteur } }
    private val hist = FloatArray(parPhase)
    private var pos = 0

    fun reset() { hist.fill(0f); pos = 0 }

    /** [n] samples of [inp] to n × [facteur] samples in [out] (as many as fit). */
    fun process(inp: ShortArray, n: Int, out: ShortArray): Int {
        var o = 0
        for (m in 0 until n) {
            pos = if (pos == 0) parPhase - 1 else pos - 1
            hist[pos] = inp[m].toFloat()
            for (p in 0 until facteur) {
                if (o >= out.size) return o
                val hp = h[p]
                var acc = 0f
                var j = pos
                for (k in 0 until parPhase) {
                    acc += hp[k] * hist[j]
                    j++; if (j == parPhase) j = 0
                }
                out[o++] = acc.coerceIn(-32000f, 32000f).toInt().toShort()
            }
        }
        return o
    }
}

/**
 * The secondary channels of one receiver, run from its own thread on each
 * block (see [RxChain.canaux]). Which channels exist and where they sit is
 * given with each block ([Cible]); a channel no longer given is dropped.
 */
class CanauxSecondaires(private val debitBrut: Double = Dsp.RTL_RATE.toDouble()) {

    /** A channel for this block: its offset from the PLL (null: out of the received band) and mode. */
    data class Cible(val id: Int, val decalageHz: Double?, val mode: ModeCanal)

    /** What a channel did in the last block. */
    data class Etat(val niveauDb: Float, val crete: Float, val horsBande: Boolean, val parLeLoin: Boolean)

    private class Voie(debit: Double) {
        val canal = CanalSecondaire(debit)
        var pcm = ShortArray(0)
        var horsBande = false
    }

    private val voies = HashMap<Int, Voie>()
    @Volatile private var etats: Map<Int, Etat> = emptyMap()

    /** Each channel's last state, by id (read from any thread). */
    fun etats(): Map<Int, Etat> = etats

    /**
     * One block for every channel of [cibles]; [sortie] gets each one's audio
     * ([Dsp.AUDIO_RATE]) as it is made, on the caller's thread.
     */
    fun traite(brutI: FloatArray, brutQ: FloatArray, nBrut: Int, e1I: FloatArray, e1Q: FloatArray, n1: Int,
               cibles: List<Cible>, sortie: (Int, ShortArray, Int) -> Unit) {
        if (voies.size > cibles.size || voies.keys.any { k -> cibles.none { it.id == k } }) {
            voies.keys.retainAll(cibles.map { it.id }.toSet())
        }
        val e = HashMap<Int, Etat>(cibles.size * 2)
        for (c in cibles) {
            val v = voies.getOrPut(c.id) { Voie(debitBrut) }
            val d = c.decalageHz
            if (d == null || abs(d) > CanalSecondaire.PORTEE_HZ) {
                if (!v.horsBande) { v.horsBande = true; v.canal.reinitialise() }
                e[c.id] = Etat(-120f, 0f, true, false)
                continue
            }
            v.horsBande = false
            if (v.canal.mode != c.mode) { v.canal.mode = c.mode; v.canal.reinitialise() }
            v.canal.decalageHz = d
            val m = v.canal.maxSortie(nBrut)
            if (v.pcm.size < m) v.pcm = ShortArray(m)
            val n = v.canal.traite(brutI, brutQ, nBrut, e1I, e1Q, n1, v.pcm)
            e[c.id] = Etat(v.canal.niveauDb, v.canal.crete, false, v.canal.parLeLoin)
            if (n > 0) sortie(c.id, v.pcm, n)
        }
        etats = e
    }

    fun vide() { voies.clear(); etats = emptyMap() }
}
