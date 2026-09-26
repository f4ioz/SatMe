/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

/**
 * All the RTL2832U and R820T arithmetic, with no Android: registers are
 * computed here and unit-tested.
 *
 * On purpose. The USB driver [RtlSdr] only moves bytes; anything that must be
 * computed (resampling ratio, PLL plan, IF encoding, gain split) lives here
 * and is tested without a dongle. There is no RTL-SDR on the build machine, so
 * this is the only way to be sure of more than a code review.
 *
 * References: librtlsdr (rtlsdr.c, r82xx.c), Rafael Micro R820T/R2 tuner.
 */
object RtlTuning {

    /** RTL2832U crystal (the R820T shares it). */
    const val XTAL = 28_800_000

    /** R82xx IF as programmed by librtlsdr. */
    const val IF_FREQ = 3_570_000

    private const val TWO_POW_22 = 4_194_304.0
    private const val TWO_POW_24 = 16_777_216.0

    // ---------------------------------------------------------------- sample rate

    /** The RTL2832U resampler doesn't cover every rate. */
    fun rateSupported(rate: Int): Boolean =
        rate > 225_000 && rate <= 3_200_000 && !(rate > 300_000 && rate <= 900_000)

    /**
     * Resampling ratio for page 1 registers 0x9f/0xa1. The two low bits are
     * always zero: the resampler ignores them.
     */
    fun resampRatio(rate: Int, xtal: Int = XTAL): Int {
        val ratio = (xtal * TWO_POW_22 / rate).toLong()
        return (ratio and 0x0ffffffcL).toInt()
    }

    /**
     * Actual rate for the ratio. It differs by under 1 ppm, but SSTV needs the
     * true value: an image takes two minutes, and a clock error shows directly
     * as slant.
     */
    fun actualRate(rate: Int, xtal: Int = XTAL): Double {
        val ratio = resampRatio(rate, xtal)
        // Hardware copies bit 27 into bit 28.
        val real = ratio.toLong() or ((ratio.toLong() and 0x08000000L) shl 1)
        if (real == 0L) return rate.toDouble()
        return xtal * TWO_POW_22 / real
    }

    /**
     * Sample-rate ppm correction (registers 0x3e/0x3f). Returns the bytes in
     * the order { reg 0x3f, reg 0x3e }.
     */
    fun freqCorrectionRegs(ppm: Int): IntArray {
        val offs = (-ppm * TWO_POW_24 / 1_000_000).toInt()
        return intArrayOf(offs and 0xff, (offs shr 8) and 0x3f)
    }

    // ------------------------------------------------------- demodulator IF

    /**
     * IF encoding (page 1 registers 0x19/0x1a/0x1b). The RTL2832U DDC brings
     * this IF down to zero, which is why an R820T still yields baseband IQ
     * although its mixer works at 3.57 MHz.
     */
    fun ifFreqRegs(ifHz: Int, xtal: Int = XTAL): IntArray {
        val v = (-(ifHz * TWO_POW_22 / xtal)).toInt()
        return intArrayOf((v shr 16) and 0x3f, (v shr 8) and 0xff, v and 0xff)
    }

    // ------------------------------------------------------------- FIR RTL

    /** Default input filter: 8 values of 8 bits, 8 of 12 bits. */
    val FIR_DEFAULT = intArrayOf(
        -54, -36, -41, -40, -32, -14, 14, 53,
        101, 156, 215, 273, 327, 372, 404, 421
    )

    /**
     * Packs the 16 coefficients into 20 bytes: eight signed 8-bit values, then
     * eight signed 12-bit values packed two per three bytes.
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

    // ------------------------------------------------------ tracking filter

    /**
     * One row of the R820T tracking filter table. The tuner has switched banks,
     * not a continuous input filter: each band has its own open-drain, RF mux
     * and capacitor setting.
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

    /** Row to program for a given LO (Hz). */
    fun muxRange(loHz: Long): MuxRange {
        val mhz = loHz / 1_000_000
        var chosen = MUX_RANGES[0]
        for (r in MUX_RANGES) { if (mhz >= r.fromMHz) chosen = r else break }
        return chosen
    }

    // ------------------------------------------------------------- PLL

    /**
     * Synthesis plan for an LO. [lockable] is false when the integer divider is
     * out of the tuner's range: the PLL won't lock, better to say so than to
     * receive noise.
     *
     * [achievedHz] is the frequency actually produced; the sigma-delta has a
     * finite step. Under ~100 Hz on VHF/UHF, invisible in NBFM, but it will
     * need software correction for SSB.
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
     * Computes the PLL plan. [vcoFineTune] comes from tuner register 0x04
     * (bits 5:4) and adjusts the divider; the R820T reference is 2, so reading
     * 2 leaves [divNum] unchanged.
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
        var vcoFra = ((vcoFreq - 2L * xtal * nint) / 1000L).toInt()   // kHz

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

        // Actual synthesised frequency: 2 * xtal * (nint + sdm/65536) / mixDiv
        val achieved = Math.round(2.0 * xtal * (nint + sdm / 65536.0) / mixDiv)

        return PllPlan(mixDiv, divNum, nint, ni, si, sdm, sdmOff, true, achieved)
    }

    // ------------------------------------------------------------- gain

    /** R820T LNA gain steps, tenths of dB. */
    val LNA_STEPS = intArrayOf(0, 9, 13, 40, 38, 13, 31, 22, 26, 31, 26, 14, 19, 5, 35, 13)

    /** Mixer gain steps, tenths of dB. */
    val MIXER_STEPS = intArrayOf(0, 5, 10, 10, 19, 9, 10, 25, 17, 10, 8, 16, 13, 6, 3, -8)

    /** Manual gains listed by librtlsdr, tenths of dB. */
    val GAINS = intArrayOf(
        0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229,
        254, 280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439,
        445, 480, 496
    )

    /**
     * Splits a gain (tenths of dB) between LNA and mixer exactly as librtlsdr
     * does: alternately add one LNA step and one mixer step until the target
     * is reached.
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
