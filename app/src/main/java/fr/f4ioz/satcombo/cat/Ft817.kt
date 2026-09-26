/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

import android.content.Context
import android.hardware.usb.UsbManager
import fr.f4ioz.satcombo.usb.UsbPermission
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A device's key: serial number if any, else vendor/product plus USB tree position.
 *
 * File-level because both classes need it (single rig to open, pair to list and
 * probe); a key computed two different ways would designate nothing.
 */
internal fun cleDe(dev: android.hardware.usb.UsbDevice): String =
    IdentiteUsb.cle(
        runCatching { dev.serialNumber }.getOrNull(),
        dev.vendorId, dev.productId, dev.deviceName)

/** A USB-serial adapter as shown in the RX/TX assignment UI. */
data class UsbSerialInfo(
    val serial: String?,        // USB serial number, missing on many chips
    val label: String,          // product name or device path
    val deviceName: String,     // /dev/bus/usb/…
    val hasPermission: Boolean,
    /**
     * Key designating this adapter: serial number or fallback identity.
     * This is what gets remembered — a PL2303TA has no serial number.
     */
    val cle: String = serial.orEmpty(),
    /** Frequency read on this cable, if a rig answered. */
    val freqLueHz: Long? = null,
    /** Already probed? Tells "not yet" from "silent". */
    val sonde: Boolean = false,
)

/**
 * Yaesu FT-817/818 CAT over one USB-serial adapter.
 *
 * Protocol: 5-byte frames — 4 parameter bytes then 1 opcode. CAT is always on.
 * Serial settings are 8 data bits, NO parity, TWO stop bits (8N2!), at the rate
 * set in the rig's menu #14 (4800 default, 9600 or 38400).
 *
 *  - set frequency : 4 BCD bytes (8 digits, unit 10 Hz) + 0x01
 *  - read freq+mode: 00 00 00 00 0x03 → 5 bytes back (4 BCD + mode)
 *  - set mode      : <mode> 00 00 00 + 0x07
 *  - CTCSS/DCS mode: <0x8A off | 0x4A encoder | 0x2A enc+dec> 00 00 00 + 0x0A
 *  - CTCSS tone    : 2 BCD bytes (tenths of Hz, e.g. 06 70 = 67.0) + 0x0B
 *  - read TX status: 00 00 00 00 0xF7 → 1 byte, bit7 SET while receiving
 *
 * Talks to a [SerialLink], so [attach] can plug in an [Ft817Sim].
 */
class Ft817Cat(private val context: Context? = null) {

    private var link: SerialLink? = null
    var boundSerial: String? = null
        private set
    val isOpen: Boolean get() = link != null

    /**
     * One conversation at a time on the line.
     *
     * Yaesu CAT has **no delimiter and no address**: a reply is recognised only
     * by counting bytes. Two concurrent questions yield indistinguishable
     * replies, each picking up the other's.
     *
     * This happened: the Doppler loop writes while the TX-status poll asks
     * twice a second. The poll's `drain` ate Doppler bytes, and the Doppler ACK
     * was read as the status byte — a TX indicator lighting at random, and a
     * frequency read back at a tenth of its value, plausible and wrong. It
     * looked port-dependent; it was only timing.
     *
     * So a whole transaction (question, reply, ACK) runs under this lock.
     */
    private val fil = kotlinx.coroutines.sync.Mutex()

    /** Gap between frames: 25 ms on a real rig. */
    var pacingMs: Long = 25L

    /** Plugs in any serial line — a real cable or a simulated rig. */
    fun attach(l: SerialLink) { link = l }

    /** Opens the adapter matching key [deviceSerial] (see [IdentiteUsb]) at [baud], 8N2. */
    suspend fun open(deviceSerial: String?, baud: Int): Boolean = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext false
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(um)
            .filter { um.hasPermission(it.device) }

        // Match by key, not serial number: requiring a serial meant a cable
        // without one (e.g. PL2303TA) was never opened.
        val cles = drivers.map { cleDe(it.device) }
        val choisie = IdentiteUsb.resout(deviceSerial, cles) ?: return@withContext false
        val driver = drivers.getOrNull(cles.indexOf(choisie)) ?: return@withContext false
        val conn = um.openDevice(driver.device) ?: return@withContext false
        val p = driver.ports.firstOrNull() ?: return@withContext false
        runCatching {
            p.open(conn)
            p.setParameters(baud, 8, UsbSerialPort.STOPBITS_2, UsbSerialPort.PARITY_NONE)
            link = UsbSerialLink(p)
            boundSerial = choisie
        }.isSuccess
    }

    fun close() {
        runCatching { link?.close() }
        link = null; boundSerial = null
    }

    private fun frameOf(p1: Int, p2: Int, p3: Int, p4: Int, op: Int) =
        byteArrayOf(p1.toByte(), p2.toByte(), p3.toByte(), p4.toByte(), op.toByte())

    /**
     * An ACK was not collected: a byte is still on the line. While set, the
     * next question drains properly instead of taking a quick look.
     */
    @Volatile private var residu = false

    /**
     * Discards leftover bytes before a question.
     *
     * The quick look (1 ms) only sees bytes **already arrived**; a USB-serial
     * adapter can hold bytes up to 16 ms before passing them up. Hence
     * [patient], used when an ACK is known to be missing.
     */
    private fun drain(l: SerialLink, patient: Boolean = false) {
        val scratch = ByteArray(16)
        var guard = 0
        val attente = if (patient) 60 else 1
        while (guard++ < 8) {
            if (runCatching { l.read(scratch, attente) }.getOrDefault(0) <= 0) break
        }
        residu = false
    }

    /**
     * Collects the ACK byte that follows every write command.
     *
     * The FT-817 answers `00` to each command. Leaving it on the line made the
     * next read start one byte early: the frequency came back at a tenth or a
     * hundredth of its value — plausible, unflagged, wrong.
     */
    private fun eatAck(l: SerialLink) {
        val one = ByteArray(1)
        // The timeout is a ceiling, not a cost: read returns as soon as the
        // byte arrives. The old 60 ms (1 ms with zero pacing) was sometimes too
        // short and the ACK stayed on the line.
        //
        // That caused the TX indicator flickering while turning the dial: a
        // leftover `00` was read by the next TX-status query, and `00` has the
        // top bit clear, which on the FT-817 means "transmitting".
        val n = runCatching { l.read(one, if (pacingMs > 0) 150 else 5) }.getOrDefault(0)
        if (n > 0) {
            CatJournal.log(false, one, CatDecode.describeYaesu(one, fromRig = true))
            residu = false
        } else residu = true
    }

    private suspend fun cmd(p1: Int, p2: Int, p3: Int, p4: Int, op: Int): Boolean =
        withContext(Dispatchers.IO) { fil.withLock {
            val l = link ?: return@withLock false
            val f = frameOf(p1, p2, p3, p4, op)
            val ok = l.write(f, 500)
            CatJournal.log(true, f, CatDecode.describeYaesu(f, fromRig = false))
            if (pacingMs > 0) kotlinx.coroutines.delay(pacingMs)   // half-duplex
            eatAck(l)
            ok
        } }

    /**
     * Sends a question and gathers exactly [want] reply bytes.
     *
     * No delimiter, so count. At 4800 baud five bytes take ~10 ms and rarely
     * arrive in one read.
     */
    private fun ask(l: SerialLink, f: ByteArray, want: Int, timeoutMs: Long): ByteArray? {
        // Anything left over belongs to the previous command.
        drain(l, patient = residu)
        if (!l.write(f, 500)) return null
        CatJournal.log(true, f, CatDecode.describeYaesu(f, fromRig = false))
        val acc = ByteArray(want)
        val scratch = ByteArray(16)
        var got = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (got < want) {
            val n = runCatching { l.read(scratch, 200) }.getOrDefault(0)
            if (n > 0) {
                val take = minOf(n, want - got)
                System.arraycopy(scratch, 0, acc, got, take)
                got += take
            } else if (pacingMs == 0L) break
            if (System.currentTimeMillis() >= deadline) break
        }
        if (got < want) return null
        CatJournal.log(false, acc,
            CatDecode.describeYaesu(acc, fromRig = true, lastOp = f[4].toInt() and 0xFF))
        return acc
    }

    /** Set frequency (Hz). FT-817 resolution is 10 Hz, 8 BCD digits big-endian. */
    suspend fun setFrequency(hz: Long): Boolean {
        val b = CatDecode.yaesuFreq(hz)
        return cmd(b[0].toInt() and 0xFF, b[1].toInt() and 0xFF,
            b[2].toInt() and 0xFF, b[3].toInt() and 0xFF, 0x01)
    }

    /** Read frequency (Hz) + mode byte, or null. Accumulates the 5-byte reply
     *  across partial USB reads (slow 4800 baud link). */
    suspend fun readFrequencyAndMode(): Pair<Long, Int>? = withContext(Dispatchers.IO) { fil.withLock {
        val l = link ?: return@withLock null
        val acc = ask(l, frameOf(0, 0, 0, 0, 0x03), 5, 600) ?: return@withLock null
        val hz = CatDecode.yaesuFreqOf(acc) ?: return@withLock null
        hz to (acc[4].toInt() and 0xFF)
    } }

    suspend fun readFrequency(): Long? = readFrequencyAndMode()?.first

    /** Set operating mode: LSB/USB/CW/CWR/AM/FM/DIG/PKT. */
    suspend fun setMode(mode: String): Boolean = cmd(modeByte(mode), 0, 0, 0, 0x07)

    /**
     * TX access tone in tenths of Hz (670 = 67.0 Hz), zero to turn it off.
     * Two big-endian BCD bytes: 88.5 Hz gives `08 85`.
     */
    suspend fun setCtcss(tenthHz: Int): Boolean {
        // The two tone frames form one command: split apart, a frequency write
        // could slip between tone mode and tone value.
        return if (tenthHz > 0) {
            if (!CatDecode.toneInRange(tenthHz)) return false
            val b = CatDecode.toneToBcdBe(tenthHz)   // 00 <hh> <ll>
            cmd(0x4A, 0, 0, 0, 0x0A) &&
                cmd(b[1].toInt() and 0xFF, b[2].toInt() and 0xFF, 0, 0, 0x0B)
        } else cmd(0x8A, 0, 0, 0, 0x0A)
    }

    /**
     * Last status byte returned by the rig, or `null` if it said nothing.
     *
     * Diagnostic line only. Reasoning on the conclusion ("TX"/"RX") without
     * seeing the raw byte cost several versions: three possible causes needed
     * three opposite fixes, and this one byte tells them apart.
     */
    @Volatile var dernierEtatTx: Int? = null
        private set

    /** True while the rig is TRANSMITTING (PTT down), false while receiving,
     *  null if unknown. Bit 7 of the 0xF7 status byte is SET during RX. */
    suspend fun isTransmitting(): Boolean? = withContext(Dispatchers.IO) { fil.withLock {
        val l = link ?: return@withLock null
        val acc = ask(l, frameOf(0, 0, 0, 0, 0xF7), 1, 300)
        if (acc == null) { dernierEtatTx = null; return@withLock null }
        val octet = acc[0].toInt() and 0xFF
        dernierEtatTx = octet
        // Top bit clear: the FT-817 reports TX. Idle it returns 0xFF.
        (octet and 0x80) == 0
    } }

    private fun modeByte(m: String): Int = when (m.uppercase()) {
        "LSB" -> 0x00; "USB" -> 0x01; "CW" -> 0x02; "CWR" -> 0x03
        "AM" -> 0x04; "FM" -> 0x08; "DIG" -> 0x0A; "PKT" -> 0x0C
        else -> 0x01
    }
}

/**
 * The classic portable full-duplex satellite station: TWO FT-817s, one fixed on
 * RX (downlink) and one on TX (uplink), each on its own USB-serial cable.
 * Assignment is remembered by adapter key (see [IdentiteUsb]): the serial number
 * survives replugging; the fallback identity only while nothing is moved.
 */
class Ft817Pair(private val context: Context? = null) {

    val rx = Ft817Cat(context)
    val tx = Ft817Cat(context)
    val isOpen: Boolean get() = rx.isOpen || tx.isOpen
    val bothOpen: Boolean get() = rx.isOpen && tx.isOpen

    /** Plugs in two simulated rigs, for bench tests. */
    fun attach(rxLink: SerialLink, txLink: SerialLink) {
        rx.attach(rxLink); tx.attach(txLink)
    }

    /** Gap between frames, applied to both rigs. */
    var pacingMs: Long
        get() = rx.pacingMs
        set(v) { rx.pacingMs = v; tx.pacingMs = v }

    companion object {
        private const val ACTION_USB_PERMISSION = UsbPermission.ACTION_CAT
    }

    /** All recognized USB-serial adapters, with FTDI serial when readable. */
    fun listDevices(): List<UsbSerialInfo> {
        val ctx = context ?: return emptyList()
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        return UsbSerialProber.getDefaultProber().findAllDrivers(um).map { d ->
            val has = um.hasPermission(d.device)
            val serie = if (has) runCatching { d.device.serialNumber }.getOrNull() else null
            UsbSerialInfo(
                serial = serie,
                label = d.device.productName ?: "USB serial",
                deviceName = d.device.deviceName,
                hasPermission = has,
                cle = cleDe(d.device)
            )
        }
    }

    /**
     * Probes an adapter: is there a rig on it, and on which frequency?
     *
     * Solves duplex with two identical cables lacking serial numbers: the two
     * rigs are on different bands, so their answers tell which is which.
     *
     * Goes through a single [Ft817Cat] rather than re-implementing framing:
     * two copies of the same protocol would drift apart.
     */
    suspend fun sonde(cle: String, baud: Int): Long? {
        val poste = Ft817Cat(context)
        if (!poste.open(cle, baud)) return null
        val hz = runCatching { poste.readFrequency() }.getOrNull()
        poste.close()
        return hz?.takeIf { IdentiteUsb.freqPlausible(it) }
    }

    /** Ask permission for every recognized adapter that lacks it. */
    fun requestPermissions() {
        val ctx = context ?: return
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        UsbSerialProber.getDefaultProber().findAllDrivers(um).forEach { d ->
            // One request code per adapter: the pair asks twice in a row, and
            // two PendingIntents with the same code would collapse into one.
            UsbPermission.ensure(ctx, um, d.device, ACTION_USB_PERMISSION, d.device.deviceId)
        }
    }

    /** Open both rigs by their remembered serials. Returns rxOk to txOk. */
    suspend fun open(rxSerial: String?, txSerial: String?, baud: Int): Pair<Boolean, Boolean> {
        val a = if (!rxSerial.isNullOrBlank()) rx.open(rxSerial, baud) else false
        val b = if (!txSerial.isNullOrBlank()) tx.open(txSerial, baud) else false
        return a to b
    }

    fun close() { rx.close(); tx.close() }

    /** Doppler pair: downlink to the RX rig, uplink to the TX rig. The TX write
     *  is skipped while that rig is actually transmitting (half-duplex safety —
     *  same behaviour as SatPC32). */
    suspend fun setPair(downlinkHz: Long, uplinkHz: Long) {
        if (rx.isOpen) rx.setFrequency(downlinkHz)
        if (tx.isOpen && tx.isTransmitting() != true) tx.setFrequency(uplinkHz)
    }

    suspend fun setUplink(uplinkHz: Long) {
        if (tx.isOpen && tx.isTransmitting() != true) tx.setFrequency(uplinkHz)
    }

    suspend fun readDownlink(): Long? = if (rx.isOpen) rx.readFrequency() else null

    /**
     * Reads back the TX rig's VFO, so its dial can act as a control (a gesture
     * you can't see can't be followed).
     *
     * Not read while transmitting: the rig answers badly, and there is nothing
     * to correct while talking.
     */
    suspend fun readUplink(): Long? =
        if (tx.isOpen && tx.isTransmitting() != true) tx.readFrequency() else null

    suspend fun setModes(downlink: String, uplink: String) {
        if (rx.isOpen) rx.setMode(downlink)
        if (tx.isOpen) tx.setMode(uplink)
    }

    /** CTCSS on the TX rig only (the uplink carries the tone). */
    suspend fun setCtcss(tenthHz: Int) { if (tx.isOpen) tx.setCtcss(tenthHz) }

    /** Human-readable link test of both rigs. */
    suspend fun testLink(): String {
        val r = if (!rx.isOpen) "—" else rx.readFrequency()?.let { "%.5f MHz".format(it / 1e6) } ?: "?"
        val t = if (!tx.isOpen) "—" else tx.readFrequency()?.let { "%.5f MHz".format(it / 1e6) } ?: "?"
        return "RX: $r · TX: $t"
    }
}
