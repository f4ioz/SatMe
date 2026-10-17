/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv.dvbs

import fr.f4ioz.satcombo.datv.ModulateurDatv
import fr.f4ioz.satcombo.datv.s2.FiltreRrc
import fr.f4ioz.satcombo.datv.s2.Interpolateur
import fr.f4ioz.satcombo.datv.s2.ModulateurDvbs2
import fr.f4ioz.satcombo.datv.s2.SourceTs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A DVB-S modulator (EN 300 421) for one transport stream: sync byte
 * inverted every 8 packets and energy dispersal, RS(204,188), convolutional
 * interleaving, the K = 7 code punctured to [taux], QPSK (Gray, bit 0 → +),
 * RRC roll-off 0.35 at [sps] samples per symbol, out as 16-bit I/Q for a
 * 12-bit DAC like the DVB-S2 modulator. DVB-S has no dummy frame: when the
 * source has no packet ready a null packet goes instead (the stream must
 * come at [TauxDvbs.debitTs], CBR, for none to be needed).
 */
class ModulateurDvbs(
    val taux: TauxDvbs,
    val sps: Int,
    private val source: SourceTs,
    reculDb: Double = ModulateurDvbs2.RECUL_DB,
    portee: Int = 16,
) : ModulateurDatv {
    init { require(sps >= 2) }

    val alpha = ALPHA
    private val interp = Interpolateur(FiltreRrc(ALPHA, sps, portee))
    private val echelle = (ModulateurDvbs2.PLEIN * 10.0.pow(-reculDb / 20)).toFloat()

    private val paquet = ByteArray(188)
    private val rs = ByteArray(204)
    private val entrelaceur = EntrelaceurDvbs()
    private val codeur = CodeurConvolutif(taux)
    /** Coded bits of one packet (2 per input bit at most), plus one left from the previous. */
    private val codes = ByteArray(204 * 8 * 2 + 1)
    private var nCodes = 0
    private var posCodes = 0
    /** Packet number in its group of 8. */
    private var groupe = 0

    init {
        // The interleaver filled with 11 null packets first: no constant carrier while its FIFOs empty their zeros.
        repeat(11) {
            nul(paquet)
            Dispersion.applique(paquet, 0, groupe)
            groupe = (groupe + 1) and 7
            Rs204.encode(paquet, 0, rs)
            for (b in rs) entrelaceur.pousse(b)
        }
    }

    private val tampon = FloatArray(2 * sps)
    private var posTampon = 2 * sps

    @Volatile override var paquets = 0L; private set
    @Volatile var nuls = 0L; private set
    @Volatile override var ecretages = 0L; private set
    @Volatile var symboles = 0L; private set

    override fun partVide(): Double { val t = paquets + nuls; return if (t > 0) nuls.toDouble() / t else 0.0 }

    /** The next packet through the channel coding, its coded bits after any left over. */
    private fun paquetSuivant() {
        if (source.disponibles() > 0) { source.lis(paquet); paquets++ }
        else { nul(paquet); nuls++ }
        Dispersion.applique(paquet, 0, groupe)
        groupe = (groupe + 1) and 7
        Rs204.encode(paquet, 0, rs)
        // Whatever was left (an odd bit) moved to the start.
        var n = 0
        for (k in posCodes until nCodes) codes[n++] = codes[k]
        for (b in rs) {
            val v = entrelaceur.pousse(b).toInt()
            for (j in 7 downTo 0) n += codeur.bit((v shr j) and 1, codes, n)
        }
        nCodes = n; posCodes = 0
    }

    override fun remplis(dst: ShortArray, paires: Int) {
        var o = 0
        val fin = 2 * paires
        while (o < fin) {
            if (posTampon == tampon.size) {
                while (nCodes - posCodes < 2) paquetSuivant()
                val i = if (codes[posCodes].toInt() == 0) R2 else -R2
                val q = if (codes[posCodes + 1].toInt() == 0) R2 else -R2
                posCodes += 2
                interp.pousse(i, q, tampon, 0)
                symboles++
                posTampon = 0
            }
            val v = tampon[posTampon++] * echelle
            var r = v.roundToInt()
            if (r > ModulateurDvbs2.MAX || r < -ModulateurDvbs2.MAX) { ecretages++; r = r.coerceIn(-ModulateurDvbs2.MAX, ModulateurDvbs2.MAX) }
            dst[o++] = r.toShort()
        }
    }

    companion object {
        /** DVB-S's only roll-off. */
        const val ALPHA = 0.35
        private val R2 = (1.0 / sqrt(2.0)).toFloat()

        /** A null packet (PID 0x1FFF, payload only, 0xFF). */
        fun nul(p: ByteArray) {
            p[0] = 0x47; p[1] = 0x1F; p[2] = 0xFF.toByte(); p[3] = 0x10
            java.util.Arrays.fill(p, 4, 188, 0xFF.toByte())
        }
    }
}
