/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.sdr

/**
 * Toute l'arithmétique du RTL2832U et du tuner R820T, sans une seule ligne
 * d'Android : les registres se calculent ici et se testent au banc.
 *
 * C'est volontaire. Le pilote USB [RtlSdr] ne fait que transporter des octets ;
 * dès qu'un chiffre doit être calculé (rapport de rééchantillonnage, plan de
 * PLL, encodage de la fréquence intermédiaire, découpage du gain), le calcul
 * vit ici et une suite de tests le vérifie sans clé branchée. Il n'y a pas de
 * RTL-SDR dans l'atelier de compilation : c'est la seule façon d'avoir une
 * certitude sur autre chose que la relecture du code.
 *
 * Références : librtlsdr (rtlsdr.c, r82xx.c), le tuner Rafael Micro R820T/R2.
 */
object RtlTuning {

    /** Quartz du RTL2832U (et du R820T, qui s'y raccroche). */
    const val XTAL = 28_800_000

    /** Fréquence intermédiaire du R82xx telle que la programme librtlsdr. */
    const val IF_FREQ = 3_570_000

    private const val TWO_POW_22 = 4_194_304.0
    private const val TWO_POW_24 = 16_777_216.0

    // ---------------------------------------------------------------- débit

    /** Le rééchantillonneur du RTL2832U ne couvre pas tout le spectre. */
    fun rateSupported(rate: Int): Boolean =
        rate > 225_000 && rate <= 3_200_000 && !(rate > 300_000 && rate <= 900_000)

    /**
     * Rapport de rééchantillonnage écrit dans les registres 0x9f/0xa1 de la
     * page 1. Les deux bits de poids faible sont toujours nuls : le
     * rééchantillonneur ne les regarde pas.
     */
    fun resampRatio(rate: Int, xtal: Int = XTAL): Int {
        val ratio = (xtal * TWO_POW_22 / rate).toLong()
        return (ratio and 0x0ffffffcL).toInt()
    }

    /**
     * Débit réellement obtenu pour un rapport donné. Il diffère du débit
     * demandé de moins d'un millionième, mais le moteur SSTV doit connaître le
     * vrai chiffre : une image met deux minutes à descendre, et une erreur
     * d'horloge se lit directement comme une inclinaison.
     */
    fun actualRate(rate: Int, xtal: Int = XTAL): Double {
        val ratio = resampRatio(rate, xtal)
        // Le bit 27 est recopié sur le bit 28 par le matériel.
        val real = ratio.toLong() or ((ratio.toLong() and 0x08000000L) shl 1)
        if (real == 0L) return rate.toDouble()
        return xtal * TWO_POW_22 / real
    }

    /**
     * Correction de fréquence d'échantillonnage en ppm (registres 0x3e/0x3f).
     * Renvoie les deux octets dans l'ordre { reg 0x3f, reg 0x3e }.
     */
    fun freqCorrectionRegs(ppm: Int): IntArray {
        val offs = (-ppm * TWO_POW_24 / 1_000_000).toInt()
        return intArrayOf(offs and 0xff, (offs shr 8) and 0x3f)
    }

    // ------------------------------------------------------- FI du démodul.

    /**
     * Encodage de la fréquence intermédiaire (registres 0x19/0x1a/0x1b de la
     * page 1). Le DDC du RTL2832U descend cette FI à zéro, ce qui est la
     * raison pour laquelle un R820T fournit quand même de l'IQ en bande de
     * base alors que son mélangeur travaille à 3,57 MHz.
     */
    fun ifFreqRegs(ifHz: Int, xtal: Int = XTAL): IntArray {
        val v = (-(ifHz * TWO_POW_22 / xtal)).toInt()
        return intArrayOf((v shr 16) and 0x3f, (v shr 8) and 0xff, v and 0xff)
    }

    // ------------------------------------------------------------- FIR RTL

    /** Réponse du filtre d'entrée par défaut : 8 valeurs 8 bits, 8 valeurs 12 bits. */
    val FIR_DEFAULT = intArrayOf(
        -54, -36, -41, -40, -32, -14, 14, 53,
        101, 156, 215, 273, 327, 372, 404, 421
    )

    /**
     * Empaquette les 16 coefficients en 20 octets : les huit premiers sont des
     * entiers signés 8 bits, les huit suivants des entiers signés 12 bits
     * tassés deux par trois octets.
     */
    fun packFir(fir: IntArray = FIR_DEFAULT): ByteArray {
        require(fir.size == 16) { "16 coefficients attendus" }
        val out = ByteArray(20)
        for (i in 0 until 8) {
            require(fir[i] in -128..127) { "coefficient 8 bits hors bornes" }
            out[i] = fir[i].toByte()
        }
        var i = 0
        while (i < 8) {
            val v0 = fir[8 + i]
            val v1 = fir[8 + i + 1]
            require(v0 in -2048..2047 && v1 in -2048..2047) { "coefficient 12 bits hors bornes" }
            out[8 + i * 3 / 2] = (v0 shr 4).toByte()
            out[8 + i * 3 / 2 + 1] = ((v0 shl 4) or ((v1 shr 8) and 0x0f)).toByte()
            out[8 + i * 3 / 2 + 2] = v1.toByte()
            i += 2
        }
        return out
    }

    // ------------------------------------------------------ filtre d'accord

    /**
     * Une tranche de la table de filtres d'accord du R820T. Le tuner n'a pas de
     * filtre d'entrée continu mais une série de bancs commutés : à chaque bande
     * sa combinaison de drain ouvert, de multiplexeur RF et de capacités.
     */
    data class MuxRange(
        val fromMHz: Int,
        val openD: Int,
        val rfMuxPoly: Int,
        val tfC: Int,
        val xtalCap0p: Int
    )

    val MUX_RANGES = listOf(
        MuxRange(0, 0x08, 0x02, 0xdf, 0x00),
        MuxRange(50, 0x08, 0x02, 0xbe, 0x00),
        MuxRange(55, 0x08, 0x02, 0x8b, 0x00),
        MuxRange(60, 0x08, 0x02, 0x7b, 0x00),
        MuxRange(65, 0x08, 0x02, 0x69, 0x00),
        MuxRange(70, 0x08, 0x02, 0x58, 0x00),
        MuxRange(75, 0x00, 0x02, 0x44, 0x00),
        MuxRange(80, 0x00, 0x02, 0x44, 0x00),
        MuxRange(90, 0x00, 0x02, 0x34, 0x00),
        MuxRange(100, 0x00, 0x02, 0x34, 0x00),
        MuxRange(110, 0x00, 0x02, 0x24, 0x00),
        MuxRange(120, 0x00, 0x02, 0x24, 0x00),
        MuxRange(140, 0x00, 0x02, 0x14, 0x00),
        MuxRange(180, 0x00, 0x02, 0x13, 0x00),
        MuxRange(220, 0x00, 0x02, 0x13, 0x00),
        MuxRange(250, 0x00, 0x02, 0x11, 0x00),
        MuxRange(280, 0x00, 0x02, 0x00, 0x00),
        MuxRange(310, 0x00, 0x41, 0x00, 0x00),
        MuxRange(588, 0x00, 0x40, 0x00, 0x00)
    )

    /** Tranche à programmer pour un oscillateur local donné (en Hz). */
    fun muxRange(loHz: Long): MuxRange {
        val mhz = loHz / 1_000_000
        var chosen = MUX_RANGES[0]
        for (r in MUX_RANGES) { if (mhz >= r.fromMHz) chosen = r else break }
        return chosen
    }

    // ------------------------------------------------------------- PLL

    /**
     * Plan de synthèse pour un oscillateur local. [lockable] est faux quand le
     * diviseur entier sort de la plage du tuner : la clé ne pourra pas
     * s'accrocher, autant le dire tout de suite plutôt que de recevoir du bruit.
     *
     * [achievedHz] est la fréquence réellement produite, qui n'est pas tout à
     * fait celle demandée : le sigma-delta a un pas fini. L'écart reste sous la
     * centaine de hertz en VHF/UHF, invisible en FM étroite, mais il faudra le
     * corriger en logiciel pour la BLU.
     */
    data class PllPlan(
        val mixDiv: Int,
        val divNum: Int,
        val nint: Int,
        val ni: Int,
        val si: Int,
        val sdm: Int,
        val sdmOff: Boolean,
        val lockable: Boolean,
        val achievedHz: Long
    )

    /**
     * Calcule le plan de PLL. [vcoFineTune] vient du registre 0x04 du tuner
     * (bits 5:4) et corrige le diviseur ; sur un R820T la valeur de référence
     * est 2, donc une lecture à 2 laisse [divNum] inchangé.
     */
    fun pllPlan(loHz: Long, xtal: Int = XTAL, vcoFineTune: Int = 2, vcoPowerRef: Int = 2): PllPlan {
        val freqKhz = (loHz + 500) / 1000
        val pllRefKhz = (xtal + 500) / 1000
        val vcoMin = 1_770_000L
        val vcoMax = 3_900_000L

        var mixDiv = 2
        var divNum = 0
        var found = false
        while (mixDiv <= 64) {
            if (freqKhz * mixDiv >= vcoMin && freqKhz * mixDiv < vcoMax) {
                var divBuf = mixDiv
                while (divBuf > 2) { divBuf = divBuf shr 1; divNum++ }
                found = true
                break
            }
            mixDiv = mixDiv shl 1
        }
        if (!found) {
            return PllPlan(0, 0, 0, 0, 0, 0, true, false, 0L)
        }

        if (vcoFineTune > vcoPowerRef) divNum -= 1
        else if (vcoFineTune < vcoPowerRef) divNum += 1

        val vcoFreq = loHz * mixDiv
        val nint = (vcoFreq / (2L * xtal)).toInt()
        var vcoFra = ((vcoFreq - 2L * xtal * nint) / 1000L).toInt()   // en kHz

        if (nint > (128 / vcoPowerRef) - 1) {
            return PllPlan(mixDiv, divNum, nint, 0, 0, 0, true, false, 0L)
        }

        val ni = (nint - 13) / 4
        val si = nint - 4 * ni - 13
        val sdmOff = vcoFra == 0

        var nSdm = 2
        var sdm = 0
        while (vcoFra > 1) {
            if (vcoFra > 2 * pllRefKhz / nSdm) {
                sdm += 32768 / (nSdm / 2)
                vcoFra -= (2 * pllRefKhz / nSdm).toInt()
                if (nSdm >= 0x8000) break
            }
            nSdm = nSdm shl 1
        }

        // Fréquence effectivement synthétisée : 2 * xtal * (nint + sdm/65536) / mixDiv
        val achieved = Math.round(2.0 * xtal * (nint + sdm / 65536.0) / mixDiv)

        return PllPlan(mixDiv, divNum, nint, ni, si, sdm, sdmOff, true, achieved)
    }

    // ------------------------------------------------------------- gain

    /** Pas de gain du LNA du R820T, en dixièmes de dB. */
    val LNA_STEPS = intArrayOf(0, 9, 13, 40, 38, 13, 31, 22, 26, 31, 26, 14, 19, 5, 35, 13)

    /** Pas de gain du mélangeur, en dixièmes de dB. */
    val MIXER_STEPS = intArrayOf(0, 5, 10, 10, 19, 9, 10, 25, 17, 10, 8, 16, 13, 6, 3, -8)

    /** Gains manuels annoncés par librtlsdr, en dixièmes de dB. */
    val GAINS = intArrayOf(
        0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229,
        254, 280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439,
        445, 480, 496
    )

    /**
     * Répartit un gain demandé (dixièmes de dB) entre le LNA et le mélangeur,
     * exactement comme librtlsdr : on empile alternativement un cran de LNA et
     * un cran de mélangeur jusqu'à atteindre la cible.
     */
    fun gainSplit(tenthDb: Int): Pair<Int, Int> {
        var total = 0
        var lna = 0
        var mix = 0
        for (i in 0 until 15) {
            if (total >= tenthDb) break
            lna++; total += LNA_STEPS[lna]
            if (total >= tenthDb) break
            mix++; total += MIXER_STEPS[mix]
        }
        return lna to mix
    }
}
