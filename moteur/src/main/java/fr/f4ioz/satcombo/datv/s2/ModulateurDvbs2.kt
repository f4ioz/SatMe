/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv.s2

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Where the transport stream comes from: whole 188-byte packets, never waited for. */
interface SourceTs {
    /** Packets ready now. */
    fun disponibles(): Int
    /** The next packet into [dst] (188 bytes, sync byte 0x47 first); only called when one is ready. */
    fun lis(dst: ByteArray)
}

/**
 * Square-root raised cosine (5.6), [sps] samples per symbol, ±[portee]
 * symbols, a Hann window over the span (lower shoulders), Σh² = sps so a
 * unit-power symbol stream gives unit-power samples.
 */
class FiltreRrc(val alpha: Double, val sps: Int, val portee: Int = 16) {
    val coefs: DoubleArray

    init {
        val n = 2 * portee * sps + 1
        val h = DoubleArray(n) { k ->
            val t = (k - portee * sps).toDouble() / sps
            rrc(t, alpha) * (0.5 + 0.5 * cos(PI * (k - portee * sps) / (portee * sps + 1.0)))
        }
        val e = h.sumOf { it * it }
        val g = sqrt(sps / e)
        coefs = DoubleArray(n) { h[it] * g }
    }

    companion object {
        /** The RRC impulse response at [t] symbol periods. */
        fun rrc(t: Double, a: Double): Double {
            if (abs(t) < 1e-9) return 1 - a + 4 * a / PI
            if (abs(abs(t) - 1 / (4 * a)) < 1e-9)
                return a / sqrt(2.0) * ((1 + 2 / PI) * sin(PI / (4 * a)) + (1 - 2 / PI) * cos(PI / (4 * a)))
            return (sin(PI * t * (1 - a)) + 4 * a * t * cos(PI * t * (1 + a))) / (PI * t * (1 - (4 * a * t).pow(2)))
        }
    }
}

/** The polyphase interpolator: each symbol in, [FiltreRrc.sps] samples out. */
class Interpolateur(f: FiltreRrc) {
    val sps = f.sps
    private val nSym = 2 * f.portee + 1
    /** Phase p's coefficients, symbol j ago. */
    private val phases = Array(sps) { p -> FloatArray(nSym) { j -> (j * sps + p).let { if (it < f.coefs.size) f.coefs[it].toFloat() else 0f } } }
    private val hi = FloatArray(2 * nSym)
    private val hq = FloatArray(2 * nSym)
    private var pos = 0

    /** Symbol ([i], [q]) in; [sps] complex samples into [sortie] from [o] (interleaved). */
    fun pousse(i: Float, q: Float, sortie: FloatArray, o: Int) {
        pos = if (pos == 0) nSym - 1 else pos - 1
        hi[pos] = i; hi[pos + nSym] = i
        hq[pos] = q; hq[pos + nSym] = q
        for (p in 0 until sps) {
            val c = phases[p]
            var a = 0f; var b = 0f
            for (j in 0 until nSym) { a += c[j] * hi[pos + j]; b += c[j] * hq[pos + j] }
            sortie[o + 2 * p] = a; sortie[o + 2 * p + 1] = b
        }
    }
}

/**
 * A DVB-S2 modulator for one transport stream, CCM, broadcast slicing (5.1,
 * table 4): packets cut across frames, sync byte replaced by the previous
 * packet's CRC-8, BBHEADER, BB scrambling, BCH, LDPC, (8PSK interleaving),
 * mapping, PLFRAME with pilots when asked, PL scrambling (code 0); a dummy
 * PLFRAME whenever a whole DATA FIELD is not ready. Then RRC shaping at
 * [sps] samples per symbol, out as 16-bit I/Q pairs for a 12-bit DAC
 * (MSB-aligned), the RMS [reculDb] below full scale.
 */
class ModulateurDvbs2(
    val modcod: Modcod,
    val rolloff: Rolloff,
    val sps: Int,
    private val source: SourceTs,
    reculDb: Double = RECUL_DB,
    portee: Int = 16,
) {
    init {
        require(modcod.refus() == null) { modcod.refus()!! }
        require(sps >= 2)
    }

    private val bch = Bch(modcod.kbch, modcod.kldpc, modcod.courte, modcod.t)
    private val ldpc = Ldpc(modcod.taux, modcod.courte)
    private val interp = Interpolateur(FiltreRrc(rolloff.alpha, sps, portee))
    private val echelle = (PLEIN * 10.0.pow(-reculDb / 20)).toFloat()

    private val bits = ByteArray(modcod.nldpc)
    private val entrelaces = if (modcod.constellation == Constellation.PSK8) ByteArray(modcod.nldpc) else bits
    private val xfec = FloatArray(2 * modcod.nldpc / modcod.constellation.bits)
    private val champ = ByteArray(modcod.dfl / 8)
    private val paquet = ByteArray(188)
    /** Bytes of [paquet] already sent (0: none pending). */
    private var posPaquet = 0
    private var crcPrecedent = 0
    private val vide = CouchePhysique.trameVide()

    private var trame: FloatArray = FloatArray(0)
    private var posTrame = 0
    private val tampon = FloatArray(2 * sps)
    private var posTampon = 2 * sps

    /**
     * Another modulator (a new MODCOD, its own stream) whose frames follow
     * from the next frame boundary on: no frame cut, this one's shaping
     * filter carried on, only its frames' source replaced.
     */
    @Volatile var suite: ModulateurDvbs2? = null
    /** Whose frames go out now: this one, or its [suite]. */
    private var courant: ModulateurDvbs2 = this

    /** The packet [o] had begun, finished here first: the stream continuous across the change (SYNCD right). */
    private fun reprend(o: ModulateurDvbs2) {
        System.arraycopy(o.paquet, 0, paquet, 0, 188)
        posPaquet = o.posPaquet
        crcPrecedent = o.crcPrecedent
    }

    @Volatile var tramesDonnees = 0L; private set
    @Volatile var tramesVides = 0L; private set
    @Volatile var paquets = 0L; private set
    @Volatile var ecretages = 0L; private set
    @Volatile var symboles = 0L; private set

    /** The next PLFRAME's symbols: data when a DATA FIELD is ready, else a dummy one. */
    fun prochaineTrame(): FloatArray {
        val enAttente = if (posPaquet == 0) 0 else 188 - posPaquet
        if (enAttente + 188L * source.disponibles() < champ.size) { tramesVides++; return vide }
        val syncd = if (posPaquet == 0) 0 else enAttente * 8
        var o = 0
        while (o < champ.size) {
            if (posPaquet == 0) {
                source.lis(paquet)
                paquets++
                // The sync byte replaced by the previous packet's CRC-8; this one's for the next.
                val crc = Crc8.octets(paquet, 1, 187)
                paquet[0] = crcPrecedent.toByte()
                crcPrecedent = crc
            }
            val n = minOf(188 - posPaquet, champ.size - o)
            System.arraycopy(paquet, posPaquet, champ, o, n)
            o += n
            posPaquet += n
            if (posPaquet == 188) posPaquet = 0
        }
        tramesDonnees++
        return CouchePhysique.trame(modcod, fecFrame(entete(syncd), champ))
    }

    /** The BBHEADER (table 4): TS, single stream, CCM, no ISSY, no null-packet deletion, roll-off. */
    fun entete(syncd: Int): ByteArray {
        val h = ByteArray(10)
        h[0] = (0xF0 or rolloff.ro).toByte()
        h[1] = 0
        h[2] = (UPL shr 8).toByte(); h[3] = UPL.toByte()
        h[4] = (modcod.dfl shr 8).toByte(); h[5] = modcod.dfl.toByte()
        h[6] = 0x47
        h[7] = (syncd shr 8).toByte(); h[8] = syncd.toByte()
        h[9] = Crc8.octets(h, 0, 9).toByte()
        return h
    }

    /** BBFRAME (header + field) to XFECFRAME symbols. */
    private fun fecFrame(entete: ByteArray, champ: ByteArray): FloatArray {
        var k = 0
        for (b in entete) for (j in 7 downTo 0) bits[k++] = ((b.toInt() shr j) and 1).toByte()
        for (b in champ) for (j in 7 downTo 0) bits[k++] = ((b.toInt() shr j) and 1).toByte()
        check(k == modcod.kbch)
        BrouilleurBb.applique(bits, modcod.kbch)
        bch.encode(bits, bits, modcod.kbch)
        // LDPC over kldpc = the BCH codeword.
        ldpc.encode(bits, bits, modcod.kldpc)
        if (modcod.constellation == Constellation.PSK8) Entrelaceur.psk8(bits, entrelaces, modcod.taux)
        CouchePhysique.mappe(modcod.constellation, entrelaces, xfec, 0)
        return xfec
    }

    /** [paires] I/Q pairs into [dst] (2 shorts each), always all of them. */
    fun remplis(dst: ShortArray, paires: Int) {
        var o = 0
        val fin = 2 * paires
        while (o < fin) {
            if (posTampon == tampon.size) {
                if (posTrame == trame.size) {
                    val s = suite
                    if (s != null && s !== courant) { s.reprend(courant); courant = s }
                    trame = courant.prochaineTrame(); posTrame = 0
                }
                interp.pousse(trame[posTrame], trame[posTrame + 1], tampon, 0)
                posTrame += 2
                symboles++
                posTampon = 0
            }
            val v = tampon[posTampon++] * echelle
            var r = v.roundToInt()
            if (r > MAX || r < -MAX) { ecretages++; r = r.coerceIn(-MAX, MAX) }
            dst[o++] = r.toShort()
        }
    }

    companion object {
        const val UPL = 188 * 8
        /** 16-bit full scale. */
        const val PLEIN = 32767.0
        /** The 12-bit DAC's top code, MSB-aligned in 16 bits. */
        const val MAX = 32752
        const val RECUL_DB = 10.0
    }
}
