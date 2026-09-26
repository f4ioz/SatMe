/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer

/**
 * Built-in RTL-SDR driver: the dongle plugs into the phone's USB-C and SatMe
 * drives it directly, with no third-party app.
 *
 * An RTL-SDR is a Rafael Micro R820T/R2 tuner followed by a Realtek RTL2832U
 * DVB-T demodulator, put in "SDR mode" with DVB-T bypassed so it streams raw
 * unsigned 8-bit IQ over its bulk endpoint.
 *
 * All control goes through control transfers on endpoint 0: register block
 * read/write (demod, USB, system) and an I2C bridge to the tuner.
 * **Classic trap:** the tuner only answers while the RTL2832U "I2C repeater" is
 * open, so every R820T transaction must be wrapped in open/close.
 *
 * All arithmetic (PLL, IF, rate, gain) is in [RtlTuning], Android-free and
 * unit-tested. Only byte transport remains here.
 *
 * WARNING: beta. Not validated on hardware in the build environment.
 */
class RtlSdr(private val ctx: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "fr.f4ioz.satcombo.SDR_USB_PERMISSION"

        // RTL2832U register blocks.
        private const val BLOCK_DEMOD = 0
        private const val BLOCK_USB = 1
        private const val BLOCK_SYS = 2
        private const val BLOCK_I2C = 6

        private const val USB_SYSCTL = 0x2000
        private const val USB_EPA_CTL = 0x2148
        private const val USB_EPA_MAXPKT = 0x2158
        private const val DEMOD_CTL = 0x3000
        private const val DEMOD_CTL_1 = 0x300b

        private const val CTRL_IN = 0xC0
        private const val CTRL_OUT = 0x40
        private const val CTRL_TIMEOUT = 300

        private const val R820T_I2C = 0x34

        /**
         * Bulk transfer size, and not one byte more.
         *
         * **Trap:** on Android, usbfs rejects bulk transfers over 16 KB with a
         * bare -1. Reading 64 KB blocks meant no read ever succeeded and the
         * UI said "no more data from the dongle: unplug and replug". The
         * hardware was fine.
         *
         * 16 KB at 1.06 Msps is 7.7 ms of IQ, 130 transfers per second: that
         * is why reading is asynchronous (see [startStream]); otherwise time
         * spent demodulating a block is time the dongle isn't read.
         */
        const val XFER = 16 * 1024

        /**
         * Known vendor/product pairs. Most dongles are Realtek 0x0bda
         * 0x2832 or 0x2838; the rest are old DVB-T sticks reused by the
         * community.
         */
        private val KNOWN = setOf(
            0x0bda to 0x2832, 0x0bda to 0x2838,
            0x0413 to 0x6680, 0x0413 to 0x6f0f,
            0x0458 to 0x707f,
            0x0ccd to 0x00a9, 0x0ccd to 0x00b3, 0x0ccd to 0x00b4, 0x0ccd to 0x00b5,
            0x0ccd to 0x00b7, 0x0ccd to 0x00b8, 0x0ccd to 0x00b9, 0x0ccd to 0x00c0,
            0x0ccd to 0x00c6, 0x0ccd to 0x00d3, 0x0ccd to 0x00d7, 0x0ccd to 0x00e0,
            0x1554 to 0x5020,
            0x15f4 to 0x0131, 0x15f4 to 0x0133,
            0x185b to 0x0620, 0x185b to 0x0650, 0x185b to 0x0680,
            0x1b80 to 0xd393, 0x1b80 to 0xd394, 0x1b80 to 0xd395, 0x1b80 to 0xd397,
            0x1b80 to 0xd398, 0x1b80 to 0xd39d, 0x1b80 to 0xd3a4, 0x1b80 to 0xd3a8,
            0x1b80 to 0xd3af, 0x1b80 to 0xd3b0,
            0x1d19 to 0x1101, 0x1d19 to 0x1102, 0x1d19 to 0x1103, 0x1d19 to 0x1104,
            0x1f4d to 0xa803, 0x1f4d to 0xb803, 0x1f4d to 0xc803, 0x1f4d to 0xd286,
            0x1f4d to 0xd803
        )

        fun isRtl(d: UsbDevice): Boolean = (d.vendorId to d.productId) in KNOWN

        /** First connected RTL-SDR, or null. */
        fun find(um: UsbManager): UsbDevice? = um.deviceList.values.firstOrNull { isRtl(it) }

        /** Requests USB permission for [dev] (system dialog). */
        fun requestPermission(ctx: Context, dev: UsbDevice) {
            val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            fr.f4ioz.satcombo.usb.UsbPermission.ensure(ctx, um, dev, ACTION_USB_PERMISSION)
        }
    }

    private var conn: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    var device: UsbDevice? = null
        private set

    val isOpen: Boolean get() = conn != null

    /** Actual sample rate delivered (see [RtlTuning.actualRate]). */
    var actualSampleRate: Double = 0.0
        private set

    /** Last centre frequency actually synthesised. */
    var tunedHz: Long = 0L
        private set

    /** Last technical error, useful during beta. */
    var lastError: String? = null
        private set

    // ------------------------------------------------------ raw transfers

    private fun writeArray(block: Int, addr: Int, data: ByteArray): Boolean {
        val c = conn ?: return false
        val index = (block shl 8) or 0x10
        val n = c.controlTransfer(CTRL_OUT, 0, addr, index, data, data.size, CTRL_TIMEOUT)
        return n == data.size
    }

    private fun readArray(block: Int, addr: Int, len: Int): ByteArray? {
        val c = conn ?: return null
        val index = block shl 8
        val buf = ByteArray(len)
        val n = c.controlTransfer(CTRL_IN, 0, addr, index, buf, len, CTRL_TIMEOUT)
        return if (n == len) buf else null
    }

    private fun writeReg(block: Int, addr: Int, value: Int, len: Int): Boolean {
        val data = if (len == 1) byteArrayOf(value.toByte())
                   else byteArrayOf((value shr 8).toByte(), value.toByte())
        return writeArray(block, addr, data)
    }

    private fun demodWriteReg(page: Int, addr: Int, value: Int, len: Int): Boolean {
        val c = conn ?: return false
        val index = 0x10 or page
        val wValue = (addr shl 8) or 0x20
        val data = if (len == 1) byteArrayOf(value.toByte())
                   else byteArrayOf((value shr 8).toByte(), value.toByte())
        val n = c.controlTransfer(CTRL_OUT, 0, wValue, index, data, data.size, CTRL_TIMEOUT)
        // Like librtlsdr, always read back after a write: the demod only
        // commits the page on the next transaction.
        demodReadReg(0x0a, 0x01, 1)
        return n == data.size
    }

    private fun demodReadReg(page: Int, addr: Int, len: Int): Int {
        val c = conn ?: return -1
        val wValue = (addr shl 8) or 0x20
        val buf = ByteArray(len)
        val n = c.controlTransfer(CTRL_IN, 0, wValue, page, buf, len, CTRL_TIMEOUT)
        if (n != len) return -1
        return if (len == 1) (buf[0].toInt() and 0xff)
               else ((buf[0].toInt() and 0xff) shl 8) or (buf[1].toInt() and 0xff)
    }

    /** Opens or closes the I2C path to the tuner. */
    private fun i2cRepeater(on: Boolean) {
        demodWriteReg(1, 0x01, if (on) 0x18 else 0x10, 1)
    }

    // ------------------------------------------------------------- tuner

    /** Bit-reversal table: the R820T returns its bytes bit-reversed. */
    private val bitrevLut = intArrayOf(
        0x0, 0x8, 0x4, 0xc, 0x2, 0xa, 0x6, 0xe, 0x1, 0x9, 0x5, 0xd, 0x3, 0xb, 0x7, 0xf)

    private fun bitrev(b: Int): Int =
        (bitrevLut[b and 0x0f] shl 4) or bitrevLut[(b shr 4) and 0x0f]

    /** Shadow of tuner registers: it can't be read back register by register. */
    private val shadow = IntArray(32)

    private fun tunerWrite(reg: Int, values: IntArray): Boolean {
        for (i in values.indices) {
            val r = reg + i
            if (r in 5..31) shadow[r] = values[i] and 0xff
        }
        // The RTL2832U accepts at most 8 bytes per I2C transaction, register
        // index included.
        var pos = 0
        while (pos < values.size) {
            val size = minOf(7, values.size - pos)
            val data = ByteArray(size + 1)
            data[0] = (reg + pos).toByte()
            for (i in 0 until size) data[i + 1] = values[pos + i].toByte()
            if (!writeArray(BLOCK_I2C, R820T_I2C, data)) return false
            pos += size
        }
        return true
    }

    private fun tunerWriteReg(reg: Int, value: Int): Boolean = tunerWrite(reg, intArrayOf(value))

    /** Masked write: only the bits in [mask] change. */
    private fun tunerWriteMask(reg: Int, value: Int, mask: Int): Boolean {
        val cur = if (reg in 5..31) shadow[reg] else 0
        val v = (cur and mask.inv()) or (value and mask)
        return tunerWriteReg(reg, v)
    }

    /**
     * Tuner read. The R820T can't read from an arbitrary address: it always
     * dumps from register 0, bit-reversed.
     */
    private fun tunerRead(len: Int): IntArray? {
        val raw = readArray(BLOCK_I2C, R820T_I2C, len) ?: return null
        return IntArray(len) { bitrev(raw[it].toInt() and 0xff) }
    }

    /** R820T init sequence (registers 0x05 to 0x1f). */
    private val r820tInit = intArrayOf(
        0x83, 0x32, 0x75,
        0xc0, 0x40, 0xd6, 0x6c,
        0xf5, 0x63, 0x75, 0x68,
        0x6c, 0x83, 0x80, 0x00,
        0x0f, 0x00, 0xc0, 0x30,
        0x48, 0xcc, 0x60, 0x00,
        0x54, 0xae, 0x4a, 0xc0
    )

    private fun tunerInit(): Boolean {
        i2cRepeater(true)
        try {
            if (!tunerWrite(0x05, r820tInit)) return false

            // IF filter calibration: VGA to zero, calibrate at 56 MHz, read
            // back the code. Failure is not fatal: the filter keeps its wide
            // default, fine for NBFM.
            tunerWriteMask(0x0c, 0x00, 0x0f)   // VGA = 0
            tunerWriteMask(0x13, 49, 0x3f)     // version
            tunerWriteMask(0x1d, 0x00, 0x38)   // LT gain test
            tunerWriteMask(0x0f, 0x04, 0x04)   // calibration clock
            setTunerPll(56_000_000L)
            tunerWriteMask(0x0b, 0x10, 0x10)   // trigger
            Thread.sleep(2)
            tunerWriteMask(0x0b, 0x00, 0x10)
            tunerWriteMask(0x0f, 0x00, 0x04)   // calibration clock off
            val cal = tunerRead(5)
            val code = if (cal != null) cal[4] and 0x0f else 0
            // 0x0f means calibration failed: fall back to neutral.
            val filtCode = if (code == 0x0f) 0 else code
            tunerWriteMask(0x0a, 0x10 or filtCode, 0x1f)  // band-pass filter
            tunerWriteMask(0x0b, 0x6b, 0xef)              // high corner, 1.0 MHz

            tunerWriteMask(0x07, 0x00, 0x80)   // no image filter
            tunerWriteMask(0x06, 0x10, 0x30)   // +3 dB, 6 MHz
            tunerWriteMask(0x1e, 0x60, 0x60)   // extension at LNA gain max-1
            tunerWriteMask(0x05, 0x00, 0x80)   // loop-through on
            tunerWriteMask(0x1f, 0x00, 0x80)
            tunerWriteMask(0x0f, 0x00, 0x80)   // filter not widened
            tunerWriteMask(0x19, 0x60, 0x60)   // minimum polyphase current
            return true
        } finally {
            i2cRepeater(false)
        }
    }

    /** Programs the tuner PLL to [loHz]; returns the achieved frequency. */
    private fun setTunerPll(loHz: Long): Long {
        tunerWriteMask(0x10, 0x00, 0x10)   // refdiv = 1
        tunerWriteMask(0x1a, 0x00, 0x0c)   // autotune 128 kHz
        tunerWriteMask(0x12, 0x80, 0xe0)   // VCO current

        val probe = tunerRead(5)
        val fine = if (probe != null) (probe[4] and 0x30) shr 4 else 2
        val plan = RtlTuning.pllPlan(loHz, RtlTuning.XTAL, fine)
        if (!plan.lockable) { lastError = "PLL hors plage ($loHz Hz)"; return 0L }

        tunerWriteMask(0x10, (plan.divNum shl 5) and 0xe0, 0xe0)
        tunerWriteReg(0x14, plan.ni + (plan.si shl 6))
        tunerWriteMask(0x12, if (plan.sdmOff) 0x08 else 0x00, 0x08)
        tunerWriteReg(0x16, (plan.sdm shr 8) and 0xff)
        tunerWriteReg(0x15, plan.sdm and 0xff)

        // Two lock attempts: if the first fails, raise VCO current, which
        // unsticks most cheap dongles.
        var locked = false
        for (i in 0 until 2) {
            val st = tunerRead(3)
            if (st != null && (st[2] and 0x40) != 0) { locked = true; break }
            if (i == 0) tunerWriteMask(0x12, 0x60, 0xe0)
        }
        if (!locked) lastError = "PLL non verrouillée"
        tunerWriteMask(0x1a, 0x08, 0x08)   // autotune 8 kHz
        return if (locked) plan.achievedHz else 0L
    }

    /** Input tracking filter for the requested LO. */
    private fun setTunerMux(loHz: Long) {
        val r = RtlTuning.muxRange(loHz)
        tunerWriteMask(0x17, r.openD, 0x08)
        tunerWriteMask(0x1a, r.rfMuxPoly, 0xc3)
        tunerWriteReg(0x1b, r.tfC)
        tunerWriteMask(0x10, r.xtalCap0p or 0x08, 0x0b)
        tunerWriteMask(0x08, 0x00, 0x3f)
        tunerWriteMask(0x09, 0x00, 0x3f)
    }

    // -------------------------------------------------------------- open

    /** Opens the dongle and puts it in SDR mode. */
    fun open(dev: UsbDevice): Boolean {
        lastError = null
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!um.hasPermission(dev)) { lastError = "permission USB refusée"; return false }
        val c = um.openDevice(dev) ?: run { lastError = "ouverture USB impossible"; return false }
        val itf = dev.getInterface(0)
        if (!c.claimInterface(itf, true)) {
            c.close(); lastError = "interface USB déjà prise"; return false
        }
        var ep: UsbEndpoint? = null
        for (i in 0 until itf.endpointCount) {
            val e = itf.getEndpoint(i)
            if (e.direction == UsbConstants.USB_DIR_IN &&
                e.type == UsbConstants.USB_ENDPOINT_XFER_BULK) { ep = e; break }
        }
        if (ep == null) {
            c.releaseInterface(itf); c.close(); lastError = "endpoint bulk absent"; return false
        }
        conn = c; iface = itf; epIn = ep; device = dev

        if (!initBaseband()) { close(); lastError = lastError ?: "init du démodulateur"; return false }
        if (!tunerInit()) { close(); lastError = "tuner R820T muet"; return false }
        configureForR82xx()
        return true
    }

    fun close() {
        val c = conn
        if (c != null) {
            stopStream()
            runCatching {
                // Stop the endpoint, then put the demod to sleep: otherwise the
                // dongle stays hot and sometimes refuses to reopen.
                writeReg(BLOCK_USB, USB_EPA_CTL, 0x1002, 2)
                i2cRepeater(true)
                tunerWriteReg(0x06, 0xb1)   // tuner standby
                i2cRepeater(false)
                demodWriteReg(0, 0x0c, 0x00, 1)
                writeReg(BLOCK_SYS, DEMOD_CTL, 0x20, 1)
            }
            runCatching { iface?.let { c.releaseInterface(it) } }
            runCatching { c.close() }
        }
        conn = null; iface = null; epIn = null; device = null
    }

    /** Wakes the RTL2832U and enters SDR mode (DVB-T decoder bypassed). */
    private fun initBaseband(): Boolean {
        writeReg(BLOCK_USB, USB_SYSCTL, 0x09, 1)
        writeReg(BLOCK_USB, USB_EPA_MAXPKT, 0x0002, 2)
        writeReg(BLOCK_USB, USB_EPA_CTL, 0x1002, 2)

        writeReg(BLOCK_SYS, DEMOD_CTL_1, 0x22, 1)
        writeReg(BLOCK_SYS, DEMOD_CTL, 0xe8, 1)

        demodWriteReg(1, 0x01, 0x14, 1)   // soft reset
        demodWriteReg(1, 0x01, 0x10, 1)

        demodWriteReg(1, 0x15, 0x00, 1)   // no spectrum inversion
        demodWriteReg(1, 0x16, 0x0000, 2)
        for (i in 0 until 6) demodWriteReg(1, 0x16 + i, 0x00, 1)

        val fir = RtlTuning.packFir()
        for (i in fir.indices) demodWriteReg(1, 0x1c + i, fir[i].toInt() and 0xff, 1)

        demodWriteReg(0, 0x19, 0x05, 1)   // SDR mode, digital AGC off
        demodWriteReg(1, 0x93, 0xf0, 1)
        demodWriteReg(1, 0x94, 0x0f, 1)
        demodWriteReg(1, 0x11, 0x00, 1)
        demodWriteReg(1, 0x04, 0x00, 1)   // no RF/IF AGC loop
        demodWriteReg(0, 0x61, 0x60, 1)   // PID filter off
        demodWriteReg(0, 0x06, 0x80, 1)   // default ADC I/Q path
        demodWriteReg(1, 0xb1, 0x1b, 1)   // zero-IF, DC and IQ correction
        demodWriteReg(0, 0x0d, 0x83, 1)   // no 4.096 MHz clock on TP_CK0
        return true
    }

    /**
     * R820T-specific setup: not zero-IF but 3.57 MHz on a single ADC channel;
     * the RTL2832U DDC brings it down to baseband, hence spectrum inversion
     * must be turned back on.
     */
    private fun configureForR82xx() {
        demodWriteReg(1, 0xb1, 0x1a, 1)   // zero-IF off
        demodWriteReg(0, 0x08, 0x4d, 1)   // ADC I input only
        val ifr = RtlTuning.ifFreqRegs(RtlTuning.IF_FREQ)
        demodWriteReg(1, 0x19, ifr[0], 1)
        demodWriteReg(1, 0x1a, ifr[1], 1)
        demodWriteReg(1, 0x1b, ifr[2], 1)
        demodWriteReg(1, 0x15, 0x01, 1)   // spectrum inversion
    }

    // -------------------------------------------------------------- settings

    /** Sets the sample rate; returns the actual rate, or 0 if refused. */
    fun setSampleRate(rate: Int): Double {
        if (!RtlTuning.rateSupported(rate)) { lastError = "débit non supporté"; return 0.0 }
        val ratio = RtlTuning.resampRatio(rate)
        demodWriteReg(1, 0x9f, (ratio shr 16) and 0xffff, 2)
        demodWriteReg(1, 0xa1, ratio and 0xffff, 2)
        demodWriteReg(1, 0x01, 0x14, 1)
        demodWriteReg(1, 0x01, 0x10, 1)
        actualSampleRate = RtlTuning.actualRate(rate)
        return actualSampleRate
    }

    /** Clock correction, ppm. */
    fun setFreqCorrection(ppm: Int) {
        val r = RtlTuning.freqCorrectionRegs(ppm)
        demodWriteReg(1, 0x3f, r[0], 1)
        demodWriteReg(1, 0x3e, r[1], 1)
    }

    /** Sets the centre frequency; returns the achieved one, or 0. */
    fun setCenterFreq(hz: Long): Long {
        if (conn == null) return 0L
        val lo = hz + RtlTuning.IF_FREQ
        i2cRepeater(true)
        try {
            setTunerMux(lo)
            val achieved = setTunerPll(lo)
            if (achieved == 0L) return 0L
            tunedHz = achieved - RtlTuning.IF_FREQ
            return tunedHz
        } finally {
            i2cRepeater(false)
        }
    }

    /** Tuner gain in tenths of dB, or null for auto. */
    fun setGain(tenthDb: Int?) {
        i2cRepeater(true)
        try {
            if (tenthDb == null) {
                tunerWriteMask(0x05, 0x00, 0x10)   // LNA auto
                tunerWriteMask(0x07, 0x10, 0x10)   // mixer auto
                tunerWriteMask(0x0c, 0x0b, 0x9f)   // VGA fixed 26.5 dB
            } else {
                tunerWriteMask(0x05, 0x10, 0x10)   // LNA manual
                tunerWriteMask(0x07, 0x00, 0x10)   // mixer manual
                tunerWriteMask(0x0c, 0x08, 0x9f)   // VGA fixed 16.3 dB
                val (lna, mix) = RtlTuning.gainSplit(tenthDb)
                tunerWriteMask(0x05, lna, 0x0f)
                tunerWriteMask(0x07, mix, 0x0f)
            }
        } finally {
            i2cRepeater(false)
        }
    }

    /** RTL2832U digital AGC (independent of tuner gain). */
    fun setAgc(on: Boolean) {
        demodWriteReg(0, 0x19, if (on) 0x25 else 0x05, 1)
    }

    // ------------------------------------------------------------- IQ stream

    /** Flushes USB buffers before reading. */
    fun resetBuffer() {
        writeReg(BLOCK_USB, USB_EPA_CTL, 0x1002, 2)
        writeReg(BLOCK_USB, USB_EPA_CTL, 0x0000, 2)
    }

    /**
     * Synchronous IQ read. Returns bytes read, 0 on timeout, -1 if the link is
     * dead. Samples are unsigned 8-bit, I then Q, centred on 127.5.
     *
     * Fallback only: between calls nobody reads the dongle and its buffers
     * overflow. Fine to check a dongle answers, not to decode SSTV. The normal
     * path is [startStream]/[readStream].
     */
    fun read(buf: ByteArray, timeoutMs: Int = 1000): Int {
        val c = conn ?: return -1
        val e = epIn ?: return -1
        return c.bulkTransfer(e, buf, minOf(buf.size, XFER), timeoutMs)
    }

    // --------------------------------------------------- async IQ stream

    /** In-flight requests. Only touched from the reader thread. */
    private val inflight = ArrayList<UsbRequest>()

    /**
     * Queues [depth] transfers with the kernel.
     *
     * While one block is demodulated the others keep filling, so the stream
     * never breaks. Essential for SSTV: an image spans two minutes of
     * continuous audio, and a few-millisecond gap shifts every following line.
     *
     * Eight 16 KB buffers = 128 KB, ~60 ms of margin: plenty for a load spike.
     */
    fun startStream(depth: Int = 8): Boolean {
        stopStream()
        val c = conn ?: return false
        val e = epIn ?: return false
        for (i in 0 until depth) {
            val r = UsbRequest()
            if (!r.initialize(c, e)) { runCatching { r.close() }; break }
            val b = ByteBuffer.allocateDirect(XFER)
            r.clientData = b
            b.clear()
            if (!r.queue(b)) { runCatching { r.close() }; break }
            inflight += r
        }
        if (inflight.isEmpty()) lastError = "file USB refusée"
        return inflight.isNotEmpty()
    }

    /**
     * Waits for the next completed transfer, copies it and requeues the request
     * at once. Returns bytes read, 0 on timeout, -1 if the link is dead.
     */
    fun readStream(out: ByteArray, timeoutMs: Long): Int {
        val c = conn ?: return -1
        if (inflight.isEmpty()) return -1
        val r = try {
            c.requestWait(timeoutMs)
        } catch (_: java.util.concurrent.TimeoutException) {
            return 0
        } catch (_: Exception) {
            return -1
        } ?: return -1
        val b = r.clientData as? ByteBuffer ?: return -1
        // After requestWait, the buffer position is the byte count received.
        val n = minOf(b.position(), out.size)
        if (n > 0) {
            b.flip()
            b.get(out, 0, n)
        }
        b.clear()
        if (!r.queue(b)) return -1
        return n
    }

    /** Cancels in-flight transfers. Call before closing the connection. */
    fun stopStream() {
        for (r in inflight) {
            runCatching { r.cancel() }
            runCatching { r.close() }
        }
        inflight.clear()
    }
}
