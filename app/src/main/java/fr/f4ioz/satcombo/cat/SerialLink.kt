/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A serial line and nothing else: write, read, close.
 *
 * Exists so the CAT drivers can be tested without a radio. Checking by looking
 * at the front panel is not enough: a radio that acknowledges a command and
 * then ignores it looks, from outside, exactly like one that obeys.
 */
interface SerialLink {
    /** Writes [bytes]. True if the write went out. */
    fun write(bytes: ByteArray, timeoutMs: Int = 500): Boolean

    /** Reads at most [buf].size bytes. Returns the count read, zero if none. */
    fun read(buf: ByteArray, timeoutMs: Int = 300): Int

    fun close()
}

/** The real serial line, over the USB cable. */
class UsbSerialLink(private val port: UsbSerialPort) : SerialLink {
    override fun write(bytes: ByteArray, timeoutMs: Int): Boolean =
        runCatching { port.write(bytes, timeoutMs) }.isSuccess

    override fun read(buf: ByteArray, timeoutMs: Int): Int =
        runCatching { port.read(buf, timeoutMs) }.getOrDefault(0)

    override fun close() { runCatching { port.close() } }
}

/**
 * Frame log: the last 200 frames, both directions.
 *
 * When a real radio misbehaves, the only useful question is "did the frame go
 * out, and what did the rig answer?".
 */
object CatJournal {

    data class Entry(
        val tMs: Long,
        /** True for a frame sent to the rig, false for a reply. */
        val out: Boolean,
        val hex: String,
        /** Human-readable decoding, for those who don't read hex. */
        val text: String
    )

    const val DEPTH = 200

    /** Logging on. Off by default: it costs one allocation per frame. */
    @Volatile var enabled: Boolean = false

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    fun log(out: Boolean, bytes: ByteArray, text: String, tMs: Long = System.currentTimeMillis()) {
        if (!enabled) return
        val e = Entry(tMs, out, bytes.joinToString(" ") { "%02X".format(it) }, text)
        val cur = _entries.value
        _entries.value = (if (cur.size >= DEPTH) cur.drop(cur.size - DEPTH + 1) else cur) + e
    }

    fun clear() { _entries.value = emptyList() }
}
