/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A rig's IF output as an SDR receiver: the IC-9700 can send, in place of
 * its demodulated sound (« AF »), its last IF (« IF » in its menu), a real
 * signal centred on 12 kHz about 15 kHz wide — 12 kHz + Δ is the dial + Δ.
 * SatMe then demodulates it itself, as the dongle's samples.
 *
 * Sampled at [fs], the 12 kHz centre lands at 12 kHz mod fs, folded back
 * below fs/2 — and turned over — when it falls above:
 *
 * - **48 kHz** (the rig's USB sound card): 12 kHz, not turned over, the whole
 *   IF there: ±7.5 kHz.
 * - **16 kHz** (its network audio): 12 kHz folds to 16 − 12 = 4 kHz, turned
 *   over (12 kHz + Δ is seen at 4 kHz − Δ). Only ±4 kHz around it stays
 *   clear of DC and of 8 kHz: about ±3.6 kHz once the filter's edge is left.
 *
 * Mixed by −(folded centre), low-passed to what is clear, decimated by two
 * (8 kS/s from 16 kHz, 24 kS/s from 48 kHz) and conjugated when turned over:
 * the dial + Δ comes out at +Δ, frequencies rising upward.
 */
object FiPoste {
    /** The rig's IF centre (Hz) and half its width. */
    const val CENTRE_HZ = 12_000
    const val DEMI_LARGEUR_HZ = 7_500

    /**
     * How a sound rate sees the IF: where the centre lands ([repliHz]),
     * turned over or not, the half-span clear on both sides ([demiUtileHz]),
     * the complex rate after the decimation by two.
     */
    data class Plan(val fs: Int, val repliHz: Double, val inverse: Boolean, val demiUtileHz: Double) {
        val debitBase: Int get() = fs / 2
    }

    /** Null when [fs] leaves too little of the IF clear (8 kHz: the centre folds onto Nyquist). */
    fun plan(fs: Int): Plan? {
        if (fs < 8_000) return null
        val r = CENTRE_HZ.toDouble() % fs
        val inverse = r > fs / 2.0
        val a = if (inverse) fs - r else r
        // A tenth kept on the side that touches DC or Nyquist: the filter's edge.
        val utile = minOf(DEMI_LARGEUR_HZ.toDouble(), 0.9 * minOf(a, fs / 2.0 - a))
        if (utile < 1_500.0) return null
        return Plan(fs, a, inverse, utile)
    }

    /**
     * Windowed-sinc low-pass (Blackman) of exactly [n] taps at [rate], summing
     * to [gain] (an interpolator by L wants L).
     */
    fun passeBas(n: Int, coupureHz: Double, rate: Double, gain: Double = 1.0): FloatArray {
        val fc = coupureHz / rate
        val mid = (n - 1) / 2.0
        val h = DoubleArray(n)
        var s = 0.0
        for (i in 0 until n) {
            val k = i - mid
            val sinc = if (kotlin.math.abs(k) < 1e-9) 2.0 * fc else sin(2.0 * PI * fc * k) / (PI * k)
            val w = if (n == 1) 1.0 else 0.42 - 0.5 * cos(2.0 * PI * i / (n - 1)) + 0.08 * cos(4.0 * PI * i / (n - 1))
            h[i] = sinc * w; s += h[i]
        }
        return FloatArray(n) { (h[it] / s * gain).toFloat() }
    }

    /** Taps for a Blackman low-pass with [transitionHz] of edge at [rate], odd. */
    fun taps(transitionHz: Double, rate: Double): Int = (Math.ceil(5.8 * rate / transitionHz).toInt() or 1).coerceIn(15, 4_095)
}

/**
 * The real IF samples at [fs] into complex baseband at fs/2, the dial at 0 Hz
 * (see [FiPoste]). [inverseEnPlus]: turned over once more (a rig whose IF
 * runs the other way).
 */
class ConvertisseurFi(val fs: Int, inverseEnPlus: Boolean = false) {
    val plan: FiPoste.Plan = FiPoste.plan(fs) ?: throw IllegalArgumentException("$fs Hz : la FI à 12 kHz n'y tient pas")
    private val conjugue = plan.inverse != inverseEnPlus
    private val dp = 2.0 * PI * plan.repliHz / fs
    private var nco = 0.0
    // Pass to the half-span, stop where the image (and what the decimation folds back) begins.
    private val dec = run {
        // Past the IF's own edge nothing but the rig's filter skirt: cut within 1.5 kHz.
        val stop = minOf(fs / 2.0 - plan.demiUtileHz, 2.0 * plan.repliHz - plan.demiUtileHz, plan.demiUtileHz + 1_500.0)
        val tr = (stop - plan.demiUtileHz).coerceAtLeast(300.0)
        ComplexDecimator(FiPoste.passeBas(FiPoste.taps(tr, fs.toDouble()), plan.demiUtileHz + tr / 2, fs.toDouble()), 2)
    }
    private var aI = FloatArray(0)
    private var aQ = FloatArray(0)

    /** Complex rate out (S/s). */
    val debitSortie: Int get() = plan.debitBase

    fun maxSortie(n: Int): Int = n / 2 + 2

    /**
     * [n] samples of [pcm] (full scale ±32768) into [outI], [outQ] (full scale
     * ±1, a full-scale IF tone at magnitude 1); returns the count.
     */
    fun traite(pcm: ShortArray, n: Int, outI: FloatArray, outQ: FloatArray): Int {
        if (aI.size < n) { aI = FloatArray(n); aQ = FloatArray(n) }
        // ×2: a real tone is two half-size lines; the one kept gets the whole level back.
        for (k in 0 until n) {
            val x = pcm[k] / 16384f
            aI[k] = x * cos(nco).toFloat()
            aQ[k] = -x * sin(nco).toFloat()
            nco += dp
            if (nco > PI) nco -= 2.0 * PI
        }
        val m = dec.process(aI, aQ, n, outI, outQ)
        if (conjugue) for (k in 0 until m) outQ[k] = -outQ[k]
        return m
    }
}

/**
 * Complex resampling by a rational ratio (polyphase, [tapsParPhase] taps for
 * each of the L phases), from [entree] to [sortie] S/s, the content kept below
 * [coupureHz].
 */
class ReechantillonneurComplexe(entree: Int, sortie: Int, coupureHz: Double, transitionHz: Double) {
    private val l: Int
    private val m: Int
    init {
        var a = entree.toLong(); var b = sortie.toLong()
        while (b != 0L) { val t = a % b; a = b; b = t }
        l = (sortie / a).toInt(); m = (entree / a).toInt()
    }
    private val k = maxOf(2, (FiPoste.taps(transitionHz, entree.toDouble() * l) + l - 1) / l)
    private val h = FiPoste.passeBas(l * k, coupureHz, entree.toDouble() * l, l.toDouble())
    // Phase by phase, contiguous: phase p's taps for x[n], x[n−1], …
    private val poly = FloatArray(l * k).also { pp -> for (p in 0 until l) for (j in 0 until k) pp[p * k + j] = h[p + j * l] }
    private var bI = FloatArray(k - 1)
    private var bQ = FloatArray(k - 1)
    /** The next output's position, in 1/L of an input sample, from the block's start. */
    private var t = 0L

    fun maxSortie(n: Int): Int = ((n.toLong() * l) / m + 2).toInt()

    fun traite(inI: FloatArray, inQ: FloatArray, n: Int, outI: FloatArray, outQ: FloatArray): Int {
        val need = k - 1 + n
        if (bI.size < need) { bI = bI.copyOf(need); bQ = bQ.copyOf(need) }
        System.arraycopy(inI, 0, bI, k - 1, n)
        System.arraycopy(inQ, 0, bQ, k - 1, n)
        var o = 0
        val fin = n.toLong() * l
        while (t < fin) {
            val i = (t / l).toInt(); val p = (t % l).toInt()
            var si = 0f; var sq = 0f
            val base = i + k - 1
            val c0 = p * k
            for (j in 0 until k) { val c = poly[c0 + j]; si += c * bI[base - j]; sq += c * bQ[base - j] }
            outI[o] = si; outQ[o] = sq; o++
            t += m
        }
        t -= fin
        System.arraycopy(bI, n, bI, 0, k - 1)
        System.arraycopy(bQ, n, bQ, 0, k - 1)
        return o
    }
}

/**
 * How much of a sound sits high in its band, smoothed over a few seconds: the
 * energy of the steps over the signal's, (2·sin(πf/fs))² for a tone at f.
 * About 2 for the IF (around fs/4 at 16 and 48 kHz), well under 1 for a
 * voice the rig filtered to 3 kHz.
 */
class RapportAigus {
    @Volatile var rapport = 0.0; private set
    @Volatile var blocs = 0; private set
    private var dernier = 0

    fun observe(s: ShortArray, n: Int) {
        if (n < 8) return
        var d = 0.0; var e = 0.0
        var p = dernier
        for (k in 0 until n) { val x = (s[k] - p).toDouble(); d += x * x; e += s[k].toDouble() * s[k]; p = s[k].toInt() }
        dernier = p
        if (e / n < 100.0) return    // silence: says nothing
        rapport += (d / e - rapport) * (if (blocs < 20) 0.2 else 0.02)
        blocs++
    }

    /** Enough heard to say. */
    val juge: Boolean get() = blocs >= 50
    val fi: Boolean get() = juge && rapport > 1.2
    val af: Boolean get() = juge && rapport < 0.8
}

/**
 * The rig's IF as an [SourceIq] for [RxChain]: real blocks pushed by the
 * transport ([pousse], the network's 16 kHz or the USB card's 48 kHz) come
 * out as signed 16-bit I/Q at [Dsp.RTL_RATE] — the chain, the waterfall, the
 * recording see a receiver like any other, centred on the rig's dial.
 * Receive only: nothing here talks to the rig.
 */
class SourceFiPoste(
    val fs: Int,
    /** "reseau" or "usb", for the status. */
    val transport: String,
    inverseEnPlus: Boolean = false,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** Called once at [close]: the transport let go. */
    private val ferme: () -> Unit = {},
) : SourceIq {
    private val conv = ConvertisseurFi(fs, inverseEnPlus)
    val plan: FiPoste.Plan get() = conv.plan
    // ×4 sharp at the low rate, then to the dongle rate with a short filter: images far from the content by then.
    private val base = conv.debitSortie
    private val etage1 = ReechantillonneurComplexe(base, base * 4, plan.demiUtileHz + (base - 2 * plan.demiUtileHz) / 2, (base - 2 * plan.demiUtileHz).coerceAtLeast(300.0))
    private val etage2 = ReechantillonneurComplexe(base * 4, Dsp.RTL_RATE, base * 2.0, (base * 4 - 2 * plan.demiUtileHz) * 0.8)
    private val aigus = RapportAigus()
    private val file = ArrayBlockingQueue<ShortArray>(96)
    private var reste: ShortArray? = null
    private var resteDe = 0
    @Volatile private var ouvert = true
    @Volatile private var dernierMs = horloge()
    private var bI = FloatArray(0); private var bQ = FloatArray(0)
    private var cI = FloatArray(0); private var cQ = FloatArray(0)
    private var dI = FloatArray(0); private var dQ = FloatArray(0)
    @Volatile private var _octets = 0L
    @Volatile private var _perdus = 0L
    override val octets: Long get() = _octets
    override val perdus: Long get() = _perdus

    /** The input's level high in the band, for the warnings. */
    val rapportAigus: Double get() = aigus.rapport

    /** A block of the rig's sound ([n] real samples at [fs]). */
    @Synchronized fun pousse(pcm: ShortArray, n: Int) {
        if (!ouvert || n <= 0) return
        dernierMs = horloge()
        aigus.observe(pcm, n)
        val m1 = conv.maxSortie(n)
        if (bI.size < m1) { bI = FloatArray(m1); bQ = FloatArray(m1) }
        val n1 = conv.traite(pcm, n, bI, bQ)
        val m2 = etage1.maxSortie(n1)
        if (cI.size < m2) { cI = FloatArray(m2); cQ = FloatArray(m2) }
        val n2 = etage1.traite(bI, bQ, n1, cI, cQ)
        val m3 = etage2.maxSortie(n2)
        if (dI.size < m3) { dI = FloatArray(m3); dQ = FloatArray(m3) }
        val n3 = etage2.traite(cI, cQ, n2, dI, dQ)
        if (n3 <= 0) return
        val iq = ShortArray(2 * n3)
        for (k in 0 until n3) {
            iq[2 * k] = (dI[k] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
            iq[2 * k + 1] = (dQ[k] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
        }
        _octets += 4L * n3
        // The processing late: the oldest dropped, as a receiver's lost blocks.
        if (!file.offer(iq)) { file.poll(); _perdus++; file.offer(iq) }
    }

    /**
     * What to tell the operator, or null: the rig's sound not coming, or
     * looking like the demodulated sound (AF) and not its IF.
     */
    fun avertissement(): String? = when {
        horloge() - dernierMs > 3_000L -> "aucun son du poste depuis " + (horloge() - dernierMs) / 1000 + " s"
        aigus.af -> "le poste semble envoyer son son démodulé (AF) et non sa FI : sur l'IC-9700, régler la sortie audio " +
            (if (transport == "usb") "USB" else "LAN") + " sur IF"
        else -> null
    }

    override val iq16: Boolean get() = true

    override fun read16(buf: ShortArray, timeoutMs: Int): Int {
        if (!ouvert) return -1
        var o = 0
        val fin = System.nanoTime() + timeoutMs * 1_000_000L
        while (o < buf.size - 1) {
            val r = reste ?: run {
                val attente = if (o == 0) (fin - System.nanoTime()) / 1_000_000L else 0L
                (if (attente > 0) file.poll(attente, TimeUnit.MILLISECONDS) else file.poll())?.also { resteDe = 0 }
            } ?: break
            reste = r
            val k = minOf((buf.size - o) and 1.inv(), r.size - resteDe)
            System.arraycopy(r, resteDe, buf, o, k)
            o += k; resteDe += k
            if (resteDe >= r.size) reste = null
        }
        return if (o == 0 && !ouvert) -1 else o
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        val s = ShortArray(buf.size)
        val n = read16(s, timeoutMs)
        for (k in 0 until maxOf(0, n)) buf[k] = ((s[k].toInt() shr 8) + 128).coerceIn(0, 255).toByte()
        return n
    }

    // The rig tunes itself (CAT): nothing to set here.
    override fun setSampleRate(rate: Int): Double = Dsp.RTL_RATE.toDouble()
    override fun setFreqCorrection(ppm: Int) {}
    override fun setCenterFreq(hz: Long): Long = hz
    override fun setGain(tenthDb: Int?) {}
    override fun setAgc(on: Boolean) {}

    override fun close() {
        if (!ouvert) return
        ouvert = false
        file.clear()
        runCatching { ferme() }
    }
}

/**
 * A made-up IF as the rig would send it at [fs], for trials with no rig (the
 * simulated IC-9700): noise, a CW call keyed 1.1 kHz above the dial, a weak
 * carrier 2.6 kHz below, a two-tone 1 and 1.7 kHz below (as a voice in LSB),
 * and, where the IF is wide enough, a carrier 5.5 kHz above.
 */
class FiSimulee(val fs: Int, graine: Long = 1L) {
    private val rnd = java.util.Random(graine)
    private var t = 0L
    // The call, then two seconds of silence, again and again.
    private val manip = fr.f4ioz.satcombo.cw.DecodeurCw.manipulation("CQ CQ DE F4IOZ F4IOZ K", 18) + listOf(false to 2_000.0)
    private val fins = manip.runningFold(0.0) { a, p -> a + p.second }.drop(1)
    private val cycleMs = fins.last()
    private val large = (FiPoste.plan(fs)?.demiUtileHz ?: 0.0) > 6_000.0

    private fun cle(ms: Double): Double {
        val m = ms % cycleMs
        val i = fins.indexOfFirst { m < it }.coerceAtLeast(0)
        if (!manip[i].first) return 0.0
        val debut = if (i == 0) 0.0 else fins[i - 1]
        // 4 ms edges: no clicks.
        return minOf(1.0, (m - debut) / 4.0, (fins[i] - m) / 4.0)
    }

    /** [n] samples of the IF into [out], full scale ±32768. */
    fun bloc(out: ShortArray, n: Int) {
        for (k in 0 until n) {
            val s = t.toDouble() / fs
            fun ton(d: Double) = cos(2 * PI * (FiPoste.CENTRE_HZ + d) * s)
            var v = 0.02 * rnd.nextGaussian()
            v += 0.12 * cle(s * 1000) * ton(1_100.0)
            v += 0.02 * ton(-2_600.0)
            v += 0.05 * (0.6 + 0.4 * sin(2 * PI * 0.7 * s)) * (ton(-1_000.0) + ton(-1_700.0))
            if (large) v += 0.04 * ton(5_500.0)
            out[k] = (v * 32767).coerceIn(-32768.0, 32767.0).toInt().toShort()
            t++
        }
    }
}
