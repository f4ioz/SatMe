/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import java.io.Closeable

/**
 * An SDR receiver as the reception sees it, wherever it is: an RTL-SDR
 * dongle plugged into the phone (its USB driver) or reached on the network
 * (rtl_tcp, on a PC or a small computer by the antenna), or an ADALM-PLUTO
 * on the network ([PlutoSdr]). The IQ comes as unsigned bytes, I then Q,
 * whole pairs — what [RxChain] takes; a receiver with more bits says so
 * with [iq16] and gives signed 16-bit pairs through [read16].
 */
interface SourceIq : Closeable {
    /** The rate obtained. */
    fun setSampleRate(rate: Int): Double
    fun setFreqCorrection(ppm: Int)
    /** The centre frequency obtained (the one asked, when the dongle says nothing back). */
    fun setCenterFreq(hz: Long): Long
    /** Tenths of dB, or null: automatic. */
    fun setGain(tenthDb: Int?)
    fun setAgc(on: Boolean)
    fun resetBuffer() {}
    /** Continuous reading started (false: [read] block by block). */
    fun startStream(depth: Int = 8): Boolean = false
    fun readStream(out: ByteArray, timeoutMs: Long): Int = read(out, timeoutMs.toInt())
    fun stopStream() {}
    /** A block into [buf]: its length, 0 when nothing came in time, -1 once the dongle is gone. */
    fun read(buf: ByteArray, timeoutMs: Int = 1000): Int

    /** Samples as signed 16-bit I, Q pairs (full scale ±32768) through [read16]: more dynamic range than bytes. */
    val iq16: Boolean get() = false
    /** As [read], in shorts (an even count): only when [iq16]. */
    fun read16(buf: ShortArray, timeoutMs: Int = 1000): Int = throw UnsupportedOperationException()
    /** Bytes received from the receiver so far, and blocks dropped because the processing was late. */
    val octets: Long get() = 0L
    val perdus: Long get() = 0L
}
