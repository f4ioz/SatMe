/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import fr.f4ioz.satcombo.visu.SondeSignal
import kotlin.math.PI
import kotlin.math.abs
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
               drapeauxAvant: Int = 32, marque: Double = MARQUE, espace: Double = ESPACE, baud: Int = BAUD): ShortArray =
        moduleTons(Hdlc.bits(trames, drapeauxAvant), frequence, niveau, marque, espace, baud)

    /** Tones (true = mark) as PCM, phase-continuous. */
    internal fun moduleTons(tons: BooleanArray, frequence: Int, niveau: Double = 0.5,
                            marque: Double = MARQUE, espace: Double = ESPACE, baud: Int = BAUD): ShortArray {
        val parBit = frequence.toDouble() / baud
        val n = (tons.size * parBit).roundToInt()
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val ton = tons[minOf((i / parBit).toInt(), tons.lastIndex)]
            phase += 2 * PI * (if (ton) marque else espace) / frequence
            if (phase > 2 * PI) phase -= 2 * PI
            out[i] = (sin(phase) * niveau * 32767).roundToInt().toShort()
        }
        return out
    }
}

/** How many received bits a frame whose FCS failed may have had wrong, to be mended ([CorrecteurHdlc]). */
enum class CorrectionBits(val bits: Int) { AUCUNE(0), UN_BIT(1), DEUX_BITS(2) }

/**
 * What the AFSK demodulator listens for: the tones and speed, and what it
 * tries besides plain AX.25. [APRS] (the default) is 1200/2200 Hz at 1200
 * baud; [HF_300] the HF packet of 300 baud, 1600/1800 Hz.
 */
data class ReglagesAfsk(
    val marque: Double = Afsk.MARQUE,
    val espace: Double = Afsk.ESPACE,
    val baud: Int = Afsk.BAUD,
    val correction: CorrectionBits = CorrectionBits.DEUX_BITS,
    /** Frames wrapped in FX.25 (Reed-Solomon around AX.25). */
    val fx25: Boolean = true,
    /** IL2P frames (NinoTNC and others). */
    val il2p: Boolean = true,
) {
    companion object {
        val APRS = ReglagesAfsk()
        val HF_300 = ReglagesAfsk(marque = 1600.0, espace = 1800.0, baud = 300)
    }
}

/** How a frame was received. */
enum class OrigineTrame { AX25, FX25, IL2P }

/**
 * One frame received, with how: plain AX.25, mended ([bitsCorriges] received
 * bits turned over), FX.25 or IL2P ([octetsCorriges] bytes the Reed-Solomon
 * code mended). [octets] is the frame with its FCS; [trame] when it is an
 * APRS (UI) frame.
 */
class Reception(
    val octets: ByteArray,
    val trame: Trame?,
    val origine: OrigineTrame,
    val bitsCorriges: Int = 0,
    val octetsCorriges: Int = 0,
    /** IL2P: its trailing CRC was there and right. */
    val crcIl2p: Boolean = false,
) {
    /** A word for the operator, or null for a plain frame heard clean: « corrigée 1 bit », « FX.25 (3 octets corrigés) ». */
    val marque: String? get() = when (origine) {
        OrigineTrame.AX25 -> if (bitsCorriges > 0) "corrigée $bitsCorriges bit" + (if (bitsCorriges > 1) "s" else "") else null
        OrigineTrame.FX25 -> "FX.25" + octets(octetsCorriges)
        OrigineTrame.IL2P -> "IL2P" + octets(octetsCorriges)
    }
    private fun octets(n: Int) = if (n == 0) "" else " ($n octet${if (n > 1) "s" else ""} corrigé${if (n > 1) "s" else ""})"
}

/** Frames heard since the start, by how. */
data class StatsAfsk(
    /** AX.25 frames heard clean. */
    val ax25: Int = 0,
    val corrigees1: Int = 0,
    val corrigees2: Int = 0,
    val fx25: Int = 0,
    val il2p: Int = 0,
    /** Bytes mended by FX.25 and IL2P together. */
    val octetsRs: Int = 0,
) {
    val total: Int get() = ax25 + corrigees1 + corrigees2 + fx25 + il2p
    /** "justes 9 · corrigées 2 (1 bit : 1, 2 bits : 1) · FX.25 1 · IL2P 0". */
    fun ligne(): String = "justes $ax25 · corrigées ${corrigees1 + corrigees2}" +
        (if (corrigees2 > 0) " (1 bit : $corrigees1, 2 bits : $corrigees2)" else "") + " · FX.25 $fx25 · IL2P $il2p"
}

/**
 * Received audio → AX.25 frames.
 *
 * A band-pass filter keeps the two tones (900–2500 Hz for APRS). Two
 * quadrature detectors measure the mark and space tones over one bit. The
 * audio from a radio has lost treble on the way (FM de-emphasis): the space
 * tone arrives weaker than the mark, by an amount that depends on the rig.
 * So several slicers run side by side, each weighing space against mark
 * differently, each with its own clock and HDLC decoder; a frame any of them
 * gets right counts once.
 *
 * Beyond plain AX.25 ([reglages]): a frame no slicer got right is mended
 * when one or two bits were wrong ([CorrecteurHdlc], using how sure each
 * slicer was of each bit); FX.25 and IL2P frames are found in the same
 * bits and mended by their Reed-Solomon code. All come out the same way.
 *
 * Works at any sample rate the recorder uses (8 to 48 kHz). Feed it the
 * capture buffers as they come; frames arrive through [surTrame].
 */
class AfskDemodulateur(
    private val frequence: Int,
    private val reglages: ReglagesAfsk = ReglagesAfsk.APRS,
    private val surTrame: (Trame) -> Unit,
) {
    private val parBit = frequence.toDouble() / reglages.baud

    /**
     * Every HDLC frame with a right FCS (the FCS removed), AX.25 or not: an
     * SSDV packet sent bare through an FM repeater (IO-86) has no address.
     */
    @Volatile var surOctets: ((ByteArray) -> Unit)? = null

    /** Every frame, with how it came (mended, FX.25, IL2P) — APRS or not. */
    @Volatile var surReception: ((Reception) -> Unit)? = null

    /** Frames since the start, by how they came. */
    @Volatile var statistiques = StatsAfsk()
        private set

    // Band-pass around the tones (900–2500 Hz for APRS): windowed-sinc low-pass minus low-pass.
    private val filtre: FloatArray = run {
        val marge = reglages.baud / 4.0
        val bas = minOf(reglages.marque, reglages.espace) - marge
        val haut = maxOf(reglages.marque, reglages.espace) + marge
        val m = ((frequence / 1000.0) * 3.2 * (Afsk.BAUD.toDouble() / reglages.baud)).roundToInt()
            .let { if (it % 2 == 0) it + 1 else it }.coerceAtLeast(15)
        val c = (m - 1) / 2.0
        FloatArray(m) { k ->
            val x = k - c
            fun sinc(fc: Double) = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val h = sinc(haut / frequence) - sinc(bas / frequence)
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
    private val pasM = 2 * PI * reglages.marque / frequence
    private val pasS = 2 * PI * reglages.espace / frequence

    /** One slicer: its own weighting, clock and decoders. */
    private inner class Tranche(val poids: Float) {
        var pll = 0
        var niveau = false
        var dernierBit = false
        val hdlc = HdlcDecodeur(surEchec = if (reglages.correction != CorrectionBits.AUCUNE) ({ s -> candidat(s) }) else null) { octets ->
            recu(octets, OrigineTrame.AX25)
        }
        val fx25 = if (reglages.fx25) Fx25Decodeur { o, c -> recu(o, OrigineTrame.FX25, octetsRs = c) } else null
        val il2p = if (reglages.il2p) Il2pDecodeur { o, info -> recu(o, OrigineTrame.IL2P, octetsRs = info.corriges, crc = info.crc) } else null
    }
    private val tranches = listOf(0.5f, 0.71f, 1f, 1.41f, 2f, 2.83f, 4f).map { Tranche(it) }
    /** The slicer the views follow: mark and space weighed alike. */
    private val centrale = tranches[2]

    /**
     * The views' tap: the tone difference's eye at the middle slicer's clock,
     * the audio's spectrum (the two tones), its NRZI bits with the HDLC flags.
     */
    val sonde = SondeSignal("afsk", "AFSK " + reglages.baud + " bauds", setOf(SondeSignal.Vue.OEIL, SondeSignal.Vue.TONS, SondeSignal.Vue.BITS),
        motifs = listOf(SondeSignal.DRAPEAU_HDLC), lsbDabord = true).also {
        it.regle(frequence.toDouble(), reglages.baud.toDouble())
        val bas = minOf(reglages.marque, reglages.espace); val haut = maxOf(reglages.marque, reglages.espace)
        it.bandeTons = maxOf(100.0, bas - (haut - bas) * 0.6) to haut + (haut - bas) * 0.6
        it.ecartAttenduHz = haut - bas
    }
    private val pasPll = ((1L shl 32) * reglages.baud / frequence).toInt()

    // One frame heard by several slicers counts once.
    private var echantillon = 0L
    private val recents = LinkedHashMap<Int, Long>()

    // Frames that failed, kept a few bits: mended only if no slicer got them right.
    private val candidats = ArrayList<SegmentHdlc>()
    private var echeance = Long.MAX_VALUE
    private var debutCandidats = 0L
    private var derniereBonne = Long.MIN_VALUE / 2

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

        val sd = sonde
        val vu = sd.actif
        if (vu) sd.audio(x, frequence.toDouble())
        for (t in tranches) {
            val pese = espace * t.poids
            val niveau = marque > pese
            val avant = t.pll
            t.pll += pasPll
            if (vu && t === centrale) {
                val v = (marque - espace) / (marque + espace + 1e-12f)
                // The clock's wrap from positive to negative is the bit's middle: phase 0 there.
                val ph = ((t.pll.toLong() and 0xFFFFFFFFL) / 4294967296.0 + 0.5) % 1.0
                refAge++
                if (avant > 0 && t.pll < 0) {
                    sd.niveau(v); sd.symbole(echantillon.toDouble()); sd.bit(if (niveau == t.dernierBit) 1 else 0)
                    // Only inside a run of one tone: across a change the detectors hold both.
                    if (refPris && (marque > espace) == tonAvant) sd.frequence(tonMesure(marque > espace))
                    tonAvant = marque > espace
                    refPris = false
                } else if (!refPris && ph >= 0.75) {
                    refPris = true; refAge = 0
                    refMI = sommeMI; refMQ = sommeMQ; refSI = sommeSI; refSQ = sommeSQ
                }
                sd.oeil(ph, v)
            }
            if (avant > 0 && t.pll < 0) {
                // Middle of a bit: NRZI, same tone as before = 1.
                val bit = niveau == t.dernierBit
                t.hdlc.bit(bit, abs(marque - pese) / (marque + pese + 1e-12f))
                t.fx25?.bit(bit)
                t.il2p?.bit(niveau)  // IL2P: no NRZI, mark = 1
                t.dernierBit = niveau
            }
            if (niveau != t.niveau) {
                // A tone change marks a bit edge: pull the clock towards it,
                // harder while hunting than once flags have been seen.
                t.pll = (t.pll * if (t.hdlc.synchro) 0.74f else 0.5f).toInt()
                t.niveau = niveau
            }
        }
        if (echantillon >= echeance) traiteCandidats()
    }

    // The views' tone measure: the detectors' phasors a quarter of a bit before the decision.
    private var refPris = false
    private var tonAvant = false
    private var refAge = 0
    private var refMI = 0f; private var refMQ = 0f; private var refSI = 0f; private var refSQ = 0f

    /**
     * The tone heard over the last quarter bit: how far the winning
     * detector's phasor turned (it stands still on its own tone).
     */
    private fun tonMesure(marque: Boolean): Float {
        if (refAge <= 0) return Float.NaN
        val (i, q, ri, rq) = if (marque) listOf(sommeMI, sommeMQ, refMI, refMQ) else listOf(sommeSI, sommeSQ, refSI, refSQ)
        val dphi = kotlin.math.atan2((q * ri - i * rq).toDouble(), (i * ri + q * rq).toDouble())
        return ((if (marque) reglages.marque else reglages.espace) - dphi * frequence / (2 * PI * refAge)).toFloat()
    }

    private fun candidat(s: SegmentHdlc) {
        if (candidats.isEmpty()) { debutCandidats = echantillon; echeance = echantillon + (3 * parBit).toLong() }
        if (candidats.size < 2 * tranches.size) candidats += s
    }

    /** The slicers have all closed the frame: if none got it right, try mending what they heard. */
    private fun traiteCandidats() {
        // An FX.25 block under way may still give this frame, mended better.
        if (tranches.any { it.fx25?.enCours == true }) { echeance = echantillon + (8 * parBit).toLong(); return }
        val l = candidats.sortedByDescending { it.bits.size }
        candidats.clear()
        echeance = Long.MAX_VALUE
        if (derniereBonne >= debutCandidats - (2 * parBit).toLong()) return
        for (s in l) {
            val c = CorrecteurHdlc.corrige(s, reglages.correction.bits) ?: continue
            recu(c.octets, OrigineTrame.AX25, bits = c.bits)
            return
        }
    }

    private fun recu(octets: ByteArray, origine: OrigineTrame, bits: Int = 0, octetsRs: Int = 0, crc: Boolean = false) {
        derniereBonne = echantillon
        if (sonde.actif) { if (origine == OrigineTrame.AX25 && bits == 0) sonde.trameEntreReperes("trame AX.25") else sonde.trame(origine.name, octets.size * 8 + 16) }
        val cle = octets.contentHashCode()
        val deja = recents[cle]
        // An FX.25 block ends long after the frame inside it (up to 255 bytes later: 1.7 s at 1200 baud).
        val fenetre = if (origine == OrigineTrame.FX25) 3L * frequence * Afsk.BAUD / reglages.baud else frequence.toLong()
        if (deja != null && echantillon - deja < fenetre) return
        recents[cle] = echantillon
        if (recents.size > 64) recents.remove(recents.keys.first())
        val s = statistiques
        statistiques = when {
            origine == OrigineTrame.FX25 -> s.copy(fx25 = s.fx25 + 1, octetsRs = s.octetsRs + octetsRs)
            origine == OrigineTrame.IL2P -> s.copy(il2p = s.il2p + 1, octetsRs = s.octetsRs + octetsRs)
            bits == 1 -> s.copy(corrigees1 = s.corrigees1 + 1)
            bits >= 2 -> s.copy(corrigees2 = s.corrigees2 + 1)
            else -> s.copy(ax25 = s.ax25 + 1)
        }
        surOctets?.let { f ->
            if (Ax25.fcsJuste(octets)) runCatching { f(octets.copyOf(octets.size - 2)) }
        }
        val t = Ax25.decode(octets)
        surReception?.let { f -> runCatching { f(Reception(octets, t, origine, bits, octetsRs, crc)) } }
        if (t == null) return
        nombre++
        surTrame(t)
    }
}
