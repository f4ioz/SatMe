/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

/**
 * Two simulated rigs.
 *
 * A simulator that always says yes proves nothing: that is how a misencoded
 * access tone, politely acknowledged and never applied, went unnoticed for
 * months. These refuse what the real rigs refuse (out-of-range tone, commands
 * forbidden on SUB in satellite mode) and count their refusals, so a test can
 * demand zero refusals for a sane sequence.
 */

/**
 * An in-memory IC-9700: two bands, satellite mode, split, VFOs.
 *
 * Can echo what it receives ("CI-V USB Echo Back" on real rigs) — the fault
 * that made the driver read back its own question.
 */
class Ic9700Sim(
    val radioAddr: Int = 0xA2,
    val ctrlAddr: Int = 0xE0,
    /** The rig simulated: the IC-910 takes its satellite mode on 0x1A 0x07, and none but the IC-9700 knows 0x25/0x26. */
    val modele: ModeleIcom = ModeleIcom.IC9700
) : SerialLink {

    var satMode: Boolean = false; private set
    var split: Boolean = false; private set
    /** Downlink: MAIN band. */
    var mainHz: Long = 435_000_000L; private set
    /** Uplink: SUB band. */
    var subHz: Long = 145_000_000L; private set
    /** True when SUB is the selected band. */
    var onSub: Boolean = false; private set
    var mainMode: Int = 0x01; private set
    var subMode: Int = 0x01; private set
    var toneOn: Boolean = false; private set
    var toneTenthHz: Int = 0; private set
    /** Keyed over CI-V (0x1C 0x00 0x01), or set by a test. */
    var transmitting: Boolean = false
    /** Every key/unkey command, in order: a test reads the sequence back. */
    val commandesPtt = ArrayList<Boolean>()

    /** Number of commands refused so far. */
    var refusals: Int = 0; private set

    /**
     * Real rig rule: **MAIN and SUB never on the same band at once**. The real
     * rig NAKs and the band doesn't change; a simulator that accepted this
     * missed the band-swap bug entirely.
     */
    var bandExclusive: Boolean = true

    /** True if setting [hz] on that VFO would put both on one band. */
    private fun collision(surSub: Boolean, hz: Long): Boolean {
        if (!bandExclusive) return false
        val b = BandPlan.band(hz)
        if (b == BandPlan.Band.AUTRE) return false
        return b == BandPlan.band(if (surSub) mainHz else subHz)
    }

    /** Send the question back before the answer, like "CI-V Echo Back". */
    var echo: Boolean = false

    /**
     * Precede each reply with an ACK. Some rigs do; a single-read driver then
     * returned the ACK and lost the real answer.
     */
    var ackBeforeReply: Boolean = false

    /** Every frame received, for tests. */
    val received = ArrayList<ByteArray>()

    private val outbox = ArrayDeque<Byte>()
    private val inbox = ArrayList<Byte>()
    private var closed = false

    override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
        if (closed) return false
        bytes.forEach { inbox += it }
        // Only complete frames are handled; the rest waits for more bytes.
        val buf = inbox.toByteArray()
        val frames = CatDecode.splitCiv(buf)
        if (frames.isNotEmpty()) {
            val consumed = frames.sumOf { it.size }
            // Deliberate shortcut: tests send no noise between frames, so the
            // remainder is a partial frame.
            val lastEnd = buf.indexOfLast { (it.toInt() and 0xFF) == CatDecode.END } + 1
            val keep = if (lastEnd in 1..buf.size) buf.copyOfRange(lastEnd, buf.size) else ByteArray(0)
            inbox.clear(); keep.forEach { inbox += it }
            if (consumed > 0) frames.forEach { handle(it) }
        }
        return true
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        var n = 0
        while (n < buf.size && outbox.isNotEmpty()) { buf[n++] = outbox.removeFirst() }
        return n
    }

    override fun close() { closed = true; outbox.clear(); inbox.clear() }

    val isClosed: Boolean get() = closed

    private fun emit(f: ByteArray) { f.forEach { outbox.addLast(it) } }

    private fun frameToCtrl(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf(0xFE.toByte(), 0xFE.toByte(), ctrlAddr.toByte(), radioAddr.toByte(),
            cmd.toByte()) + data + byteArrayOf(CatDecode.END.toByte())

    private fun ack() = emit(frameToCtrl(CatDecode.ACK))

    /** A reply, preceded by an ACK if configured. */
    private fun reply(cmd: Int, data: ByteArray) {
        if (ackBeforeReply) emit(frameToCtrl(CatDecode.ACK))
        emit(frameToCtrl(cmd, data))
    }
    private fun nak() { refusals++; emit(frameToCtrl(CatDecode.NAK)) }

    private fun handle(f: ByteArray) {
        received += f
        // Ignore frames not addressed to us: shared-bus rule, and it also
        // covers our own echo.
        if (f.size < 6) return
        val to = f[2].toInt() and 0xFF
        if (to != radioAddr) return
        if (echo) emit(f)

        val cmd = f[4].toInt() and 0xFF
        val d = f.copyOfRange(5, f.size - 1)
        fun at(i: Int) = if (d.size > i) d[i].toInt() and 0xFF else -1

        when (cmd) {
            0x03 -> reply(0x03, CatDecode.freqToBcdLe(if (onSub) subHz else mainHz))
            0x04 -> reply(0x04, byteArrayOf((if (onSub) subMode else mainMode).toByte(), 0x01))
            0x05 -> {
                val hz = CatDecode.bcdLeToFreq(d)
                if (hz == null || collision(onSub, hz)) nak() else {
                    if (onSub) subHz = hz else mainHz = hz
                    ack()
                }
            }
            0x06 -> {
                if (d.isEmpty()) nak() else {
                    if (onSub) subMode = at(0) else mainMode = at(0)
                    ack()
                }
            }
            0x07 -> when (at(0)) {
                0x00 -> { onSub = false; ack() }
                0x01 -> { onSub = true; ack() }
                0xD0 -> { onSub = false; ack() }
                0xD1 -> { onSub = true; ack() }
                else -> nak()
            }
            0x0F -> when (at(0)) {
                0x00 -> { split = false; ack() }
                0x01 -> { split = true; ack() }
                else -> nak()
            }
            0x1A -> when {
                modele.commandeSat == 0x1A && at(0) == modele.sousCommandeSat ->
                    if (d.size < 2) reply(0x1A, byteArrayOf(modele.sousCommandeSat.toByte(), if (satMode) 0x01 else 0x00))
                    else { satMode = at(1) == 1; ack() }
                else -> nak()
            }
            0x16 -> when (at(0)) {
                0x5A -> if (modele.commandeSat != 0x16) nak()
                        else if (d.size < 2) reply(0x16, byteArrayOf(0x5A, if (satMode) 0x01 else 0x00))
                        else { satMode = at(1) == 1; ack() }
                0x42 -> { toneOn = at(1) == 1; ack() }
                else -> nak()
            }
            0x1C -> when {
                at(0) != 0x00 -> nak()
                d.size < 2 -> reply(0x1C, byteArrayOf(0x00, if (transmitting) 0x01 else 0x00))
                else -> { transmitting = at(1) == 1; commandesPtt += transmitting; ack() }
            }
            0x1B -> {
                if (at(0) != 0x00) { nak(); return }
                if (d.size < 4) {
                    reply(0x1B, byteArrayOf(0x00) + CatDecode.toneToBcdBe(toneTenthHz))
                    return
                }
                val t = CatDecode.bcdBeToTone(d, 1)
                // The old encoder sent 88.5 Hz as 00 88 50 = 885.0 Hz, out of
                // range. The real rig refuses it; so does this one, and counts it.
                if (t == null || !CatDecode.toneInRange(t)) nak() else { toneTenthHz = t; ack() }
            }
            0x25, 0x26 -> {
                if (!modele.vfoDirect) { nak(); return }
                // In satellite mode these can't reach SUB on an IC-9700. They
                // are refused, not silently applied to MAIN (much worse).
                val unselected = at(0) == 0x01
                if (satMode && unselected) { nak(); return }
                if (cmd == 0x25) {
                    if (d.size < 6) {
                        reply(0x25, byteArrayOf(d.getOrElse(0) { 0 }) +
                            CatDecode.freqToBcdLe(if (unselected) subHz else mainHz))
                        return
                    }
                    val hz = CatDecode.bcdLeToFreq(d, 1)
                    if (hz == null || collision(unselected, hz)) nak() else {
                        if (unselected) subHz = hz else mainHz = hz
                        ack()
                    }
                } else {
                    if (d.size < 2) { nak(); return }
                    if (unselected) subMode = at(1) else mainMode = at(1)
                    ack()
                }
            }
            else -> nak()
        }
    }
}

/**
 * An in-memory IC-705: **one** receiver, a mode, a tone and a TX state, at
 * CI-V address 0xA4.
 *
 * It refuses what only an IC-9700 understands — satellite mode (0x16 0x5A),
 * MAIN/SUB selection (0x07 D0/D1), unselected-VFO writes (0x25/0x26) — and
 * counts those refusals: a pair driver that slipped one in would show up in
 * [refusals], as it would on the real rig.
 */
class Ic705Sim(
    val radioAddr: Int = 0xA4,
    val ctrlAddr: Int = 0xE0
) : SerialLink {

    /** Starts on HF, far from any satellite: a read-back that matches was written. */
    var hz: Long = 14_074_000L; private set
    var mode: Int = 0x01; private set
    var toneOn: Boolean = false; private set
    var toneTenthHz: Int = 0; private set
    /** Set by a test to play the operator pressing PTT. */
    var transmitting: Boolean = false
    var refusals: Int = 0; private set
    /** "CI-V USB Echo Back": the rig repeats each frame before answering. */
    var echo: Boolean = false

    private val outbox = ArrayDeque<Byte>()
    private val inbox = ArrayList<Byte>()
    private var closed = false

    override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
        if (closed) return false
        bytes.forEach { inbox += it }
        val buf = inbox.toByteArray()
        val frames = CatDecode.splitCiv(buf)
        if (frames.isNotEmpty()) {
            val lastEnd = buf.indexOfLast { (it.toInt() and 0xFF) == CatDecode.END } + 1
            val keep = if (lastEnd in 1..buf.size) buf.copyOfRange(lastEnd, buf.size) else ByteArray(0)
            inbox.clear(); keep.forEach { inbox += it }
            frames.forEach { handle(it) }
        }
        return true
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        var n = 0
        while (n < buf.size && outbox.isNotEmpty()) { buf[n++] = outbox.removeFirst() }
        return n
    }

    override fun close() { closed = true; outbox.clear(); inbox.clear() }

    private fun emit(f: ByteArray) { f.forEach { outbox.addLast(it) } }
    private fun frameToCtrl(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf(0xFE.toByte(), 0xFE.toByte(), ctrlAddr.toByte(), radioAddr.toByte(),
            cmd.toByte()) + data + byteArrayOf(CatDecode.END.toByte())
    private fun ack() = emit(frameToCtrl(CatDecode.ACK))
    private fun nak() { refusals++; emit(frameToCtrl(CatDecode.NAK)) }

    private fun handle(f: ByteArray) {
        if (f.size < 6) return
        if ((f[2].toInt() and 0xFF) != radioAddr) return
        if (echo) emit(f)
        val cmd = f[4].toInt() and 0xFF
        val d = f.copyOfRange(5, f.size - 1)
        fun at(i: Int) = if (d.size > i) d[i].toInt() and 0xFF else -1
        when (cmd) {
            0x03 -> emit(frameToCtrl(0x03, CatDecode.freqToBcdLe(hz)))
            0x04 -> emit(frameToCtrl(0x04, byteArrayOf(mode.toByte(), 0x01)))
            0x05 -> CatDecode.bcdLeToFreq(d)?.let { hz = it; ack() } ?: nak()
            0x06 -> if (d.isEmpty()) nak() else { mode = at(0); ack() }
            0x07 -> if (at(0) == 0x00 || at(0) == 0x01) ack() else nak()
            0x16 -> if (at(0) == 0x42) { toneOn = at(1) == 1; ack() } else nak()
            0x1B -> {
                if (at(0) != 0x00) { nak(); return }
                if (d.size < 4) { emit(frameToCtrl(0x1B, byteArrayOf(0x00) + CatDecode.toneToBcdBe(toneTenthHz))); return }
                val t = CatDecode.bcdBeToTone(d, 1)
                if (t == null || !CatDecode.toneInRange(t)) nak() else { toneTenthHz = t; ack() }
            }
            0x1C -> if (at(0) == 0x00)
                emit(frameToCtrl(0x1C, byteArrayOf(0x00, if (transmitting) 0x01 else 0x00))) else nak()
            else -> nak()
        }
    }
}

/**
 * An in-memory FT-817: one frequency, a mode, a tone, and the inverted status
 * bit that catches everyone once.
 *
 * Yaesu CAT has no address or delimiter: always five bytes out, and a reply
 * whose length depends on the question. Nothing to split, everything to count —
 * one extra byte shifts everything after it.
 */
class Ft817Sim : SerialLink {

    var hz: Long = 145_800_000L; private set
    var mode: Int = 0x01; private set
    var toneTenthHz: Int = 0; private set
    var toneMode: Int = 0x8A; private set
    /** True while transmitting. The status bit, however, is set on **receive**. */
    var transmitting: Boolean = false

    /** Commands not understood and left unanswered. */
    var refusals: Int = 0; private set

    val received = ArrayList<ByteArray>()

    private val outbox = ArrayDeque<Byte>()
    private val inbox = ArrayList<Byte>()
    private var closed = false

    override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
        if (closed) return false
        bytes.forEach { inbox += it }
        while (inbox.size >= 5) {
            val f = ByteArray(5) { inbox[it] }
            repeat(5) { inbox.removeAt(0) }
            handle(f)
        }
        return true
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        var n = 0
        while (n < buf.size && outbox.isNotEmpty()) { buf[n++] = outbox.removeFirst() }
        return n
    }

    override fun close() { closed = true; outbox.clear(); inbox.clear() }

    val isClosed: Boolean get() = closed

    private fun emit(vararg b: Byte) { b.forEach { outbox.addLast(it) } }

    private fun handle(f: ByteArray) {
        received += f
        fun p(i: Int) = f[i].toInt() and 0xFF
        when (p(4)) {
            0x00 -> { transmitting = true; emit(0x00) }
            0x81 -> { transmitting = false; emit(0x00) }
            0x01 -> {
                val v = CatDecode.yaesuFreqOf(f)
                if (v == null) { refusals++; return }
                hz = v; emit(0x00)
            }
            0x03 -> {
                val b = CatDecode.yaesuFreq(hz)
                emit(b[0], b[1], b[2], b[3], mode.toByte())
            }
            0x07 -> { mode = p(0); emit(0x00) }
            0x0A -> {
                if (p(0) != 0x8A && p(0) != 0x4A && p(0) != 0x2A) { refusals++; return }
                toneMode = p(0); emit(0x00)
            }
            0x0B -> {
                val t = (p(0) shr 4) * 1000 + (p(0) and 0x0F) * 100 +
                    (p(1) shr 4) * 10 + (p(1) and 0x0F)
                if (!CatDecode.toneInRange(t)) { refusals++; return }
                toneTenthHz = t; emit(0x00)
            }
            0xF7 -> {
                // Bit 7 is SET on receive, cleared on transmit. Counter-intuitive
                // but per the manual; inverting it means writing the VFO while
                // the rig transmits.
                val b = if (transmitting) 0x00 else 0x80
                emit(b.toByte())
            }
            else -> refusals++
        }
    }
}
