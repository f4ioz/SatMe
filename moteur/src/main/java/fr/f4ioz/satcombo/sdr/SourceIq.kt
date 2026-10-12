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
 * An RTL-SDR dongle as the receiver sees it, wherever it is: plugged into
 * the phone (its USB driver) or reached on the network (rtl_tcp, on a PC or
 * a small computer by the antenna). The IQ comes as unsigned bytes, I then
 * Q, whole pairs — what [RxChain] takes.
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
}
